package com.qzw.filemask.fileencoder;

import com.qzw.filemask.enums.ChooseTypeEnum;
import com.qzw.filemask.enums.FileEncoderTypeEnum;
import com.qzw.filemask.enums.MaskExceptionEnum;
import com.qzw.filemask.exception.MaskException;
import com.qzw.filemask.interfaces.FileEncoderType;
import com.qzw.filemask.interfaces.PasswordHandler;
import com.qzw.filemask.util.ByteUtil;
import com.qzw.filemask.util.PrivateDataUtils;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 加密解密抽象类
 * @author quanzongwei
 * @date 2020/1/18
 */
@Log4j2
public abstract class AbstractFileEncoder implements PasswordHandler, FileEncoderType {

    /**
     * 确保加解密过程串行执行
     */
    public static Object lock = new Object();

    /**
     * 加密入口
     */
    public void encodeFileOrDir(File fileOrDir, ChooseTypeEnum dirChooseEnum) {
        synchronized (lock) {
            if (!fileOrDir.exists()) {
                throw new MaskException(MaskExceptionEnum.FILE_NOT_EXISTS.getType(), "文件或者文件夹不存在,加密失败," + fileOrDir.getPath());
            }
            if (PrivateDataUtils.isFileMaskFile(fileOrDir)) {
                log.info("私有数据文件无需处理, {}", fileOrDir.getPath());
                return;
            }
            //文件选择方式:单文件
            if (dirChooseEnum.equals(ChooseTypeEnum.FILE_ONLY)) {
                this.mkPrivateDirIfNotExists(fileOrDir);
                executeEncrypt(fileOrDir);
            }
            //文件选择方式:文件夹
            else if (dirChooseEnum.equals(ChooseTypeEnum.CURRENT_DIR_ONLY)) {
                this.mkPrivateDirIfNotExists(fileOrDir);
                File[] files = fileOrDir.listFiles();
                if (files != null && files.length > 0) {
                    this.mkPrivateDirIfNotExists(files[0]);
                    for (File file : files) {
                        executeEncrypt(file);
                    }
                }
                executeEncrypt(fileOrDir);
            }
            //文件选择方式:级联文件夹
            else if (dirChooseEnum.equals(ChooseTypeEnum.CASCADE_DIR)) {
                this.mkPrivateDirIfNotExists(fileOrDir);
                File[] files = fileOrDir.listFiles();
                if (files != null && files.length > 0) {
                    this.mkPrivateDirIfNotExists(files[0]);
                    for (File file : files) {
                        //cascade directory
                        if (file.isDirectory()) {
                            encodeFileOrDir(file, ChooseTypeEnum.CASCADE_DIR);
                            continue;
                        }
                        executeEncrypt(file);
                    }
                }
                executeEncrypt(fileOrDir);
            }
        }
    }

    /**
     * 解密入口
     */
    public void decodeFileOrDir(File fileOrDir, ChooseTypeEnum dirChooseEnum) {
        synchronized (lock) {
            if (!fileOrDir.exists()) {
                throw new MaskException(MaskExceptionEnum.FILE_NOT_EXISTS.getType(), "文件或者文件夹不存在,解密失败, " + fileOrDir.getPath());
            }
            if (PrivateDataUtils.isFileMaskFile(fileOrDir)) {
                log.info("私有数据文件无需处理,{}", fileOrDir.getPath());
                return;
            }
            //文件选择方式:单文件
            if (dirChooseEnum.equals(ChooseTypeEnum.FILE_ONLY)) {
                executeDecrypt(fileOrDir);
            }
            //文件选择方式:文件夹
            else if (dirChooseEnum.equals(ChooseTypeEnum.CURRENT_DIR_ONLY)) {
                File[] files = fileOrDir.listFiles();
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        executeDecrypt(file);
                    }
                }
                executeDecrypt(fileOrDir);
            }
            //文件选择方式:级联文件夹
            else if (dirChooseEnum.equals(ChooseTypeEnum.CASCADE_DIR)) {
                File[] files = fileOrDir.listFiles();
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        //cascade directory
                        if (file.isDirectory()) {
                            decodeFileOrDir(file, ChooseTypeEnum.CASCADE_DIR);
                            continue;
                        }
                        executeDecrypt(file);
                    }
                }
                executeDecrypt(fileOrDir);
            }
        }
    }

    /**
     * 执行加密操作
     *
     * @param fileOrDir 目标文件或者文件夹
     */
    private void executeEncrypt(File fileOrDir) {
        FileEncoderTypeEnum fileEncoderType = getFileEncoderType();
        if (fileOrDir.isDirectory() && !fileEncoderType.isSupportEncryptDir()) {
            //加密方式不支持加密文件夹, 直接跳过, 不需要任何日志
            return;
        }
        File privateDataFile = PrivateDataUtils.getPrivateDataFile(fileOrDir);
        boolean exists = privateDataFile.exists();
        byte[] result1 = null;

        try (RandomAccessFile raf = new RandomAccessFile(privateDataFile, "rw")) {
            byte[] encodeMap = null;
            //1. 合法性校验
            if (exists) {
                //exists=true意味着私有数据长度至少是256+32字节
                byte[] flags = new byte[3];
                raf.seek(16);
                raf.read(flags);
                boolean emptyFlag = true;
                for (byte flag : flags) {
                    if (flag == (byte) 0x01) {
                        emptyFlag = false;
                    }
                }
                if (emptyFlag) {
                    encodeMap = initialPrivateFileData(raf);
                } else {
                    //检测md51 数据
                    raf.seek(0);
                    byte[] md51 = new byte[16];
                    raf.read(md51);
                    md51 = xorBySecretKey(md51);
                    if (!Arrays.equals(md51, getMd51())) {
                        log.info("文件已经被其他用户加密,您无法执行加密操作,{}", fileOrDir.getPath());
                        return;
                    }
                    //检测是否重复加密
                    boolean encrypted = encodedByCurrentUserUseTheSameType(flags, fileOrDir);
                    if (encrypted) {
                        return;
                    }
                }
            } else {
                encodeMap = initialPrivateFileData(raf);
            }
            //2. 执行加密
            byte[] extraParam = null;
            if (encodeMap == null) {
                encodeMap = new byte[256];
                raf.seek(32);
                raf.read(encodeMap);
                encodeMap = xorBySecretKey(encodeMap);
            }
            if (getFileEncoderType().equals(FileEncoderTypeEnum.FILE_CONTENT_ENCODE)) {
                extraParam = encodeMap;
            }
            byte[][] result = encryptOriginFile(fileOrDir, extraParam);
            if (result == null) {
                //加密不成功
                return;
            }
            byte[] result0 = result[0];
            result1 = result[1];
            //3. 私有数据文件处理
            if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_OR_DIR_NAME_ENCODE)) {
                raf.seek(16);
                raf.write((byte) 0x01);
                result0 = getResultByMap(result0, encodeMap, true);
                raf.seek(32 + 256 + 32);
                //n bytes
                raf.write(result0);
                //
            } else if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_HEADER_ENCODE)) {
                raf.seek(16 + 1);
                raf.write((byte) 0x01);
                result0 = getResultByMap(result0, encodeMap, true);
                raf.seek(32 + 256);
                //32 bytes
                raf.write(result0);
            } else if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_CONTENT_ENCODE)) {
                raf.seek(16 + 2);
                raf.write((byte) 0x01);
                //no operation for return bytes, it is only a success flag for this FileEncoderType
            }
        } catch (IOException ex) {
            log.info("私有数据文件打开失败, 加密操作不成功,{}", privateDataFile.getPath());
            return;
        }
        //IO操作完成后才可以执行重命名操作
        if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_OR_DIR_NAME_ENCODE)) {
            //私有数据文件重命名
            boolean b = privateDataFile.renameTo(new File(privateDataFile.getParent() + File.separatorChar + new String(result1)));
            log.info("私有数据文件是否加密成功:{}", b);

        }

    }

    /**
     * 执行解密
     *
     * @param fileOrDir 目标文件或者文件夹
     */
    protected void executeDecrypt(File fileOrDir) {
        FileEncoderTypeEnum fileEncoderType = getFileEncoderType();
        if (fileOrDir.isDirectory() && !fileEncoderType.isSupportEncryptDir()) {
            //加密方式不支持加密文件夹, 直接跳过, 不需要任何日志
            return;
        }
        File privateDataFile = PrivateDataUtils.getPrivateDataFile(fileOrDir);

        if (!privateDataFile.exists()) {
            log.info("文件从未加密过,无需解密,{}", fileOrDir.getPath());
            return;
        }
        //加密类型一
        String originName = null;
        try (RandomAccessFile raf = new RandomAccessFile(privateDataFile, "rw")) {
            //私有文件长度校验
            if (raf.length() < 32 + 256) {
                log.info("私有数据文件长度不合法,无需解密,{}", fileOrDir.getPath());
                return;
            }
            //用户合法性校验
            raf.seek(0);
            byte[] md51 = new byte[16];
            raf.read(md51);
            md51 = xorBySecretKey(md51);
            if (!Arrays.equals(md51, getMd51())) {
                log.info("当前文件已被其他用户加密或该文件未加密,您无法执行解密操作,{}", fileOrDir.getPath());
                return;
            }
            //是否使用指定方式加密
            fileEncoderType = getFileEncoderType();
            raf.seek(16);
            byte[] flagBytes = new byte[3];
            raf.read(flagBytes);
            //文件名称加密
            if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_OR_DIR_NAME_ENCODE)) {
                if (flagBytes[0] != (byte) 0x01) {
                    log.info("文件未使用加密类型一进行加密,使用加密类型一进行解密失败,{}", fileOrDir.getPath());
                    return;
                }
            }
            //文件头部加密
            else if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_HEADER_ENCODE)) {
                if (flagBytes[1] != (byte) 0x01) {
                    log.info("文件未使用加密类型二进行加密,使用加密类型二进行解密失败,{}", fileOrDir.getPath());
                    return;
                }

            }
            //文件内容加密
            else if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_CONTENT_ENCODE)) {
                if (flagBytes[2] != (byte) 0x01) {
                    log.info("文件未使用加密类型三进行加密,使用加密类型三进行解密失败,{}", fileOrDir.getPath());
                    return;
                }
            }
            //执行解密,并处理私有数据
            raf.seek(32);
            byte[] encodeMap = new byte[256];
            raf.read(encodeMap);
            encodeMap = xorBySecretKey(encodeMap);
            //加密类型一
            if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_OR_DIR_NAME_ENCODE)) {
                raf.seek(32 + 256 + 32);
                byte[] extraParam = new byte[(int) (raf.length() - (32 + 256 + 32))];
                raf.read(extraParam);
                extraParam = getResultByMap(extraParam, encodeMap, false);
                originName = new String(extraParam);
                if (decryptOriginFile(fileOrDir, extraParam)) {
                    raf.seek(16);
                    raf.write(0x00);
                    //删除历史记录
                    raf.setLength(32 + 256 + 32);
                }
            }
            //加密类型二
            if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_HEADER_ENCODE)) {
                raf.seek(32 + 256);
                byte[] extraParam = new byte[32];
                //注: raf对于超出文件长度的内容, 读不出任何数据
                raf.read(extraParam);
                extraParam = getResultByMap(extraParam, encodeMap, false);
                if (decryptOriginFile(fileOrDir, extraParam)) {
                    raf.seek(16 + 1);
                    raf.write(0x00);
                }
            }
            //加密类型三
            if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_CONTENT_ENCODE)) {
                if (decryptOriginFile(fileOrDir, encodeMap)) {
                    raf.seek(16 + 2);
                    raf.write(0x00);
                }
            }
        } catch (IOException ex) {
            log.info("私有数据文件打开失败,操作终止,{}", fileOrDir.getPath(), ex);
            return;
        }
        //加密类型一: IO操作完成后才可以执行重命名操作
        if (fileEncoderType.equals(FileEncoderTypeEnum.FILE_OR_DIR_NAME_ENCODE)) {
            //私有数据文件重命名
            boolean b = privateDataFile.renameTo(new File(privateDataFile.getParent() + File.separatorChar + originName));
            log.info("私有数据文件是否重命名成功,{}", b);

        }
    }

    /**
     * 使用xor秘钥对数据进行xor操作
     * <p>
     * 1. xor执行两次, 可以恢复原数据
     * 2. xor加密理论上无法破解(除非是已知明文攻击)
     *
     * @param text
     * @return
     */
    protected byte[] xorBySecretKey(byte[] text) {
        byte[] byte32 = this.get32byteMd5Value();
        byte[] rst = new byte[text.length];
        for (int i = 0; i < rst.length; i++) {
            rst[i] = (byte) (text[i] ^ byte32[i % (byte32.length)]);
        }
        return rst;
    }

    /**
     * 生成私有数据文件夹
     *
     * @param fileOrDir 目标文件
     */
    protected void mkPrivateDirIfNotExists(File fileOrDir) {
        File file = PrivateDataUtils.getPrivateDataDir(fileOrDir);
        if (!file.exists()) {
            file.mkdir();
            try {
                //私有数据文件夹设置为不可见
                Runtime.getRuntime().exec("attrib " + "\"" + file.getAbsolutePath() + "\"" + " +H");
            } catch (IOException e) {
                //fileMask dir is not hid that has no effect
            }
        }
    }

    /**
     * 根据encodeMap对数据进行转换
     *
     * @param bytes             字节数据
     * @param encodeMap         encodeMap
     * @param isEncodeOperation 是否是加密操作,true:加密 false:解密
     * @return
     */
    private byte[] getResultByMap(byte[] bytes, byte[] encodeMap, boolean isEncodeOperation) {
        if (isEncodeOperation) {
            for (int j = 0; j < bytes.length; j++) {
                bytes[j] = encodeMap[ByteUtil.getUnsignedByte(bytes[j])];
            }
        } else {
            byte[] decodeMap = new byte[256];
            for (int i = 0; i < encodeMap.length; i++) {
                decodeMap[ByteUtil.getUnsignedByte(encodeMap[i])] = (byte) i;
            }
            for (int j = 0; j < bytes.length; j++) {
                bytes[j] = decodeMap[ByteUtil.getUnsignedByte(bytes[j])];
            }
        }
        return bytes;
    }

    /**
     * 判断当前文件是否已经使用同一种加密类型加密过, 或者是否与其他加密类型互斥
     * <p>
     * 注:其中加密类型三和加密类型二互斥
     *
     * @param flags     加密类型标识
     * @param fileOrDir 目标文件
     */
    private boolean encodedByCurrentUserUseTheSameType(byte[] flags, File fileOrDir) {
        List<FileEncoderTypeEnum> encodedTypeList = new ArrayList<>();
        //文件名称加密
        if (flags[0] == (byte) 0x01) {
            encodedTypeList.add(FileEncoderTypeEnum.FILE_OR_DIR_NAME_ENCODE);
        }
        //文件头部加密
        if (flags[1] == (byte) 0x01) {
            encodedTypeList.add(FileEncoderTypeEnum.FILE_HEADER_ENCODE);
        }
        //文件内容加密
        if (flags[2] == (byte) 0x01) {
            encodedTypeList.add(FileEncoderTypeEnum.FILE_CONTENT_ENCODE);
        }
        for (FileEncoderTypeEnum fileEncoderTypeEnum : encodedTypeList) {
            //加密类型相同
            if (fileEncoderTypeEnum.equals(getFileEncoderType())) {
                log.info("文件或文件夹已使用相同方式加密过,无需重复加密,{}", fileOrDir.getPath());
                return true;
            }
            //加密类型二
            if (FileEncoderTypeEnum.FILE_HEADER_ENCODE.equals(getFileEncoderType())) {
                if (fileEncoderTypeEnum.equals(FileEncoderTypeEnum.FILE_CONTENT_ENCODE)) {
                    log.info("文件已使用方式3加密,不再使用方式2加密,{}", fileOrDir.getPath());
                    return true;
                }
            }
            //加密类型三
            if (FileEncoderTypeEnum.FILE_CONTENT_ENCODE.equals(getFileEncoderType())) {
                if (fileEncoderTypeEnum.equals(FileEncoderTypeEnum.FILE_HEADER_ENCODE)) {
                    log.info("文件已使用方式2加密,不再使用方式3加密,{}", fileOrDir.getPath());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 初始化私有数据文件
     */
    private byte[] initialPrivateFileData(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        //写入xor加密后的用户密码的md51的值
        raf.write(xorBySecretKey(getMd51()));
        byte[] encodeMap = generateEncodeMap();
        raf.seek(32);
        //写入xor加密后的encodeMap值(256字节)
        byte[] encryptedEncodeMap = xorBySecretKey(encodeMap);
        raf.write(encryptedEncodeMap);
        return encodeMap;

    }

    /**
     * 利用随机数生成一次性encodeMap
     */
    private byte[] generateEncodeMap() {
        byte[] encodeMap = new byte[256];
        Random rd = new Random();
        //此处的随机数可以自己定义,越随机越好
        rd.setSeed(System.currentTimeMillis() + new Random().nextInt(10000));
        byte[] rdBytes = new byte[256];
        rd.nextBytes(rdBytes);
        List<Byte> bList = new ArrayList<>();
        for (Integer i = 0; i < 256; i++) {
            bList.add(i.byteValue());
        }
        List<Byte> mappedList = new ArrayList<>();
        for (int i = 0; i < rdBytes.length; i++) {
            byte b = rdBytes[i];
            if (bList.contains(b)) {
                mappedList.add(b);
                bList.remove(bList.indexOf(b));
                bList = bList.stream().collect(Collectors.toList());
            } else {
                //conflict resolve
                int index = rd.nextInt(bList.size());
                mappedList.add(bList.get(index));
                bList.remove(index);
                bList = bList.stream().collect(Collectors.toList());
            }
        }
        for (int i = 0; i < mappedList.size(); i++) {
            encodeMap[i] = mappedList.get(i);
        }
        return encodeMap;
    }


    /**
     * 子类实现的加密方法
     *
     * @param extraParam 只有针对文件内容加密时,传入encodeMap,用于全文加密
     *
     * 对于返回值:
     * 1. 文件名称加密: byte[0]为文件原始名称, byte[1]为文件加密后的名称
     * 2. 文件头部加密: byte[0]为文件头部原始数据
     * 3. 文件内容加密: byte数据不为null,表示操作成功即可
     *
     * 返回null: 代表子类加密操作失败
     */
    protected abstract byte[][] encryptOriginFile(File fileOrDir, byte[] extraParam);

    /**
     * 子类实现的解密方法
     * @param extraParam
     * 对于参数 extraParam:
     * 1. 文件名称加密: 值为原始文件名称
     * 2. 文件头部加密: 值为原始头部数据
     * 3. 文件内容加密: 值为encodeMap
     * @return true:操作成功 false:操作失败
     */
    protected abstract boolean decryptOriginFile(File fileOrDir, byte[] extraParam);
}