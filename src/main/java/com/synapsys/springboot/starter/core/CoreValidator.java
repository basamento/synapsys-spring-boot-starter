package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.config.SynapsysProcessProperties;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.utils.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoreValidator {

    private final Client client;
    private final SynapsysProcessProperties properties;
    private static final Logger log = LoggerFactory.getLogger(CoreValidator.class);

    public CoreValidator(SynapsysProcessProperties properties, Client client) {
        this.client = client;
        this.properties = properties;
    }

    public String validateCoreUrl() {
        String coreUrl = properties.getCoreUrl();
        if (coreUrl == null || coreUrl.isBlank()) {
            throw new IllegalStateException(SynapsysLog.PREFIX + "Missing 'synapsys.core-url' property");
        }
        log.debug("{}Core URL configured: {}", SynapsysLog.PREFIX, coreUrl);
        return coreUrl;
    }

    public boolean testCoreConnection(String coreUrl) {
        String testUrl = ClientUtils.joinUrl(coreUrl, "api/health");
        log.debug("{}Testing Core connection via {}", SynapsysLog.PREFIX, testUrl);
        int responseCode;
        try {
            responseCode = this.client.makeCallAndReturnResponseCode(HttpMethod.GET, testUrl);
        } catch (Exception e) {
            log.debug("{}Core health check failed at {}", SynapsysLog.PREFIX, testUrl, e);
            return false;
        }
        if (responseCode < 200 || responseCode >= 300) {
            log.debug("{}Core health check returned status {}", SynapsysLog.PREFIX, responseCode);
            return false;
        }
        return true;
    }

}
