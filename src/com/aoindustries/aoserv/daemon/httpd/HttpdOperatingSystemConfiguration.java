/*
 * Copyright 2008-2012 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Operating system-specific configurations.
 *
 * @author  AO Industries, Inc.
 */
public enum HttpdOperatingSystemConfiguration {
    MANDRIVA_2006_0_I586 {
        public OperatingSystemConfiguration getOperatingSystemConfiguration() {
            return OperatingSystemConfiguration.MANDRIVA_2006_0_I586;
        }
        public String getDefaultPhpVersion() {
            return "5";
        }
        public String getDefaultPhpPostgresMinorVersion() {
            return "8.1";
        }
        public String getPhpCgiPath(String version) {
            if("4".equals(version)) return "/usr/php/4/bin/php";
            else if("5".equals(version)) return "/usr/php/5/bin/php-cgi";
            else throw new AssertionError("Unexpected PHP version: "+version);
        }
        public String getHttpdSitesDirectory() {
            return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.MANDRIVA_2006_0_I586);
        }
        public String getHttpdSharedTomcatsDirectory() {
            return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.MANDRIVA_2006_0_I586);
        }
        public String getAwstatsDirectory() {
            return "/var/lib/awstats";
        }
        public String getAwstatsBinDirectory() {
            return "/usr/awstats/current";
        }
    },
    REDHAT_ES_4_X86_64 {
        public OperatingSystemConfiguration getOperatingSystemConfiguration() {
            return OperatingSystemConfiguration.REDHAT_ES_4_X86_64;
        }
        public String getDefaultPhpVersion() {
            return "5";
        }
        public String getDefaultPhpPostgresMinorVersion() {
            return "8.1";
        }
        public String getPhpCgiPath(String version) {
            if("4".equals(version)) return "/opt/php-4/bin/php-cgi";
            else if("5".equals(version)) return "/opt/php-5/bin/php-cgi";
            else throw new AssertionError("Unexpected PHP version: "+version);
        }
        public String getHttpdSitesDirectory() {
            return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.REDHAT_ES_4_X86_64);
        }
        public String getHttpdSharedTomcatsDirectory() {
            return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.REDHAT_ES_4_X86_64);
        }
        public String getAwstatsDirectory() {
            return "/var/opt/awstats-6";
        }
        public String getAwstatsBinDirectory() {
            return "/opt/awstats-6";
        }
    },
    CENTOS_5_I686_AND_X86_64 {
        public OperatingSystemConfiguration getOperatingSystemConfiguration() {
            return OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64;
        }
        public String getDefaultPhpVersion() {
            return "5";
        }
        public String getDefaultPhpPostgresMinorVersion() {
            return "8.1";
        }
        public String getPhpCgiPath(String version) {
            if("4".equals(version)) return "/opt/php-4-i686/bin/php-cgi";
            else if("5".equals(version)) return "/opt/php-5-i686/bin/php-cgi";
            else throw new AssertionError("Unexpected PHP version: "+version);
        }
        public String getHttpdSitesDirectory() {
            return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
        }
        public String getHttpdSharedTomcatsDirectory() {
            return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
        }
        public String getAwstatsDirectory() {
            return "/var/opt/awstats-6";
        }
        public String getAwstatsBinDirectory() {
            return "/opt/awstats-6";
        }
    };

    /**
     * Gets the operating system configuration for this server, as
     * determined by the <code>AOServDaemon.getThisServer()</code>
     * method.
     * 
     * @see  AOServDaemon#getThisServer()
     */
    public static HttpdOperatingSystemConfiguration getHttpOperatingSystemConfiguration() throws IOException, SQLException {
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        switch(osv) {
            case OperatingSystemVersion.MANDRIVA_2006_0_I586 :
                return MANDRIVA_2006_0_I586;
            case OperatingSystemVersion.REDHAT_ES_4_X86_64 :
                return REDHAT_ES_4_X86_64;
            case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
                return CENTOS_5_I686_AND_X86_64;
            default :
                throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
        }
    }

    /**
     * Gets the configuration for general tasks.
     */
    public abstract OperatingSystemConfiguration getOperatingSystemConfiguration();

    /**
     * The default PHP version.
     */
    public abstract String getDefaultPhpVersion();

    /**
     * The version of PostgreSQL minor version used by the default PHP version.
     */
    public abstract String getDefaultPhpPostgresMinorVersion();

    /**
     * Gets the full path to the PHP CGI script for the provided PHP version.
     */
    public abstract String getPhpCgiPath(String version);

    /**
     * Gets the directory that contains the website directories.
     */
    public abstract String getHttpdSitesDirectory();

    /**
     * Gets the directory that contains the shared tomcat directories.
     */
    public abstract String getHttpdSharedTomcatsDirectory();
    
    /**
     * Gets the configuration directory used by AWStats
     */
    public String getAwstatsConfigDirectory() {
        return "/etc/awstats";
    }
    
    /**
     * Gets the main AWStats directory.
     */
    public abstract String getAwstatsDirectory();
    
    /**
     * Gets the AWStats hosts directory.
     */
    public String getAwstatsHostsDirectory() {
        return getAwstatsDirectory()+"/hosts";
    }
    
    /**
     * Gets the AWStats bin directory.
     */
    public abstract String getAwstatsBinDirectory();
    
    /**
     * Gets the AWStats icon directory.
     */
    public String getAwstatsIconDirectory() {
        return getAwstatsBinDirectory()+"/wwwroot/icon";
    }
}
