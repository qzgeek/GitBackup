package cn.qzgeek.gitbackup.storage;

import java.io.IOException;

/** 存储层统一异常：把各适配器的底层异常（HTTP/FTP/IO）收敛成一种，便于上层统一处理。 */
public class AdapterException extends IOException {
    public AdapterException(String message) { super(message); }

    public AdapterException(String message, Throwable cause) { super(message, cause); }
}
