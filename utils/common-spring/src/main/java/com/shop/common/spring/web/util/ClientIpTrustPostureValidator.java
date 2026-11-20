package com.shop.common.spring.web.util;

import com.shop.common.spring.config.TrustedProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

public class ClientIpTrustPostureValidator {

    private static final Logger log = LoggerFactory.getLogger(ClientIpTrustPostureValidator.class);

    private final String forwardHeadersStrategy;
    private final TrustedProxyProperties trustedProxyProperties;

    public ClientIpTrustPostureValidator(String forwardHeadersStrategy, TrustedProxyProperties trustedProxyProperties) {
        this.forwardHeadersStrategy = forwardHeadersStrategy;
        this.trustedProxyProperties = trustedProxyProperties;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void validate() {
        validateForwardHeadersStrategy();
        validateTrustedProxyConfiguration();
    }

    private void validateForwardHeadersStrategy() {
        String strategy = forwardHeadersStrategy == null ? "none" : forwardHeadersStrategy.trim();
        if ("none".equalsIgnoreCase(strategy)) {
            return;
        }
        throw new IllegalStateException(
                "server.forward-headers-strategy is '" + strategy + "'. The service must resolve the client "
                        + "address from the transport peer, so the framework must not rewrite the remote address "
                        + "from a caller-controlled X-Forwarded-For header. Set "
                        + "server.forward-headers-strategy=none and declare the trusted proxies with "
                        + "shop.web.trusted-proxy.cidrs and shop.web.trusted-proxy.hops.");
    }

    private void validateTrustedProxyConfiguration() {
        int hops = trustedProxyProperties.hops();
        if (hops > 0 && trustedProxyProperties.cidrs().isEmpty()) {
            throw new IllegalStateException(
                    "shop.web.trusted-proxy.hops is " + hops + " while shop.web.trusted-proxy.cidrs is empty. "
                            + "The service would trust the X-Forwarded-For header of any peer. Set "
                            + "shop.web.trusted-proxy.cidrs to the CIDRs of the reverse proxies or set "
                            + "shop.web.trusted-proxy.hops=0 to meter the transport peer only.");
        }
        if (hops > 0) {
            log.info("Service client-IP trust: trusted-proxy CIDR(s)={} trusted-proxy hops={}",
                    trustedProxyProperties.cidrs().size(), hops);
            return;
        }
        log.warn("Service client-IP trust: X-Forwarded-For is IGNORED, every client is logged with its transport "
                + "peer address. Set shop.web.trusted-proxy.cidrs and shop.web.trusted-proxy.hops when the service "
                + "runs behind a reverse proxy that overwrites X-Forwarded-For.");
    }
}
