package cn.qzgeek.gitbackup;

import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;

/**
 * 代理支持。
 *
 * <p>很多机房的出网必须走本地代理，而 JGit 7.x 的 {@code HttpConfig} 已不再读取代理配置，
 * 只能替换 {@link HttpConnectionFactory} 来强制走代理。这里只影响本插件的传输，
 * 不修改 JVM 全局系统属性，因此不会干扰服务器上其它插件的网络行为。
 *
 * <p>支持的写法：{@code http://host:port}、{@code https://host:port}、{@code socks5://host:port}。
 */
final class ProxySupport {
    private final Proxy proxy;

    private ProxySupport(Proxy proxy) { this.proxy = proxy; }

    static ProxySupport none() { return new ProxySupport(null); }

    static ProxySupport parse(String spec) {
        if (spec == null || spec.isBlank()) return none();
        String value = spec.trim();
        String lower = value.toLowerCase();
        Proxy.Type type;
        String rest;
        if (lower.startsWith("socks5://") || lower.startsWith("socks://")) {
            type = Proxy.Type.SOCKS;
            rest = value.substring(value.indexOf("://") + 3);
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            type = Proxy.Type.HTTP;
            rest = value.substring(value.indexOf("://") + 3);
        } else if (lower.contains(":")) {
            type = Proxy.Type.HTTP;
            rest = value;
        } else {
            throw new IllegalArgumentException("无法识别的代理地址: " + spec);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int at = rest.lastIndexOf('@');
        if (at >= 0) rest = rest.substring(at + 1);
        int colon = rest.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("代理地址需要 host:port: " + spec);
        String host = rest.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("代理端口非法: " + spec);
        }
        return new ProxySupport(new Proxy(type, new InetSocketAddress(host, port)));
    }

    boolean enabled() { return proxy != null; }

    /** 把代理与 SSH 会话工厂绑定到传输对象。 */
    void configure(Transport transport, org.eclipse.jgit.transport.sshd.SshdSessionFactory ssh) {
        if (transport instanceof SshTransport s && ssh != null) s.setSshSessionFactory(ssh);
        if (proxy != null && transport instanceof org.eclipse.jgit.transport.TransportHttp http)
            http.setHttpConnectionFactory(new ProxiedConnectionFactory(proxy));
    }

    private static final class ProxiedConnectionFactory implements HttpConnectionFactory {
        private final Proxy proxy;
        ProxiedConnectionFactory(Proxy proxy) { this.proxy = proxy; }

        @Override public HttpConnection create(URL url) throws IOException { return new Proxied(url, proxy); }
        @Override public HttpConnection create(URL url, Proxy ignored) throws IOException { return new Proxied(url, proxy); }
    }

    private static final class Proxied extends JDKHttpConnection {
        Proxied(URL url, Proxy proxy) throws MalformedURLException, IOException { super(url, proxy); }
    }
}
