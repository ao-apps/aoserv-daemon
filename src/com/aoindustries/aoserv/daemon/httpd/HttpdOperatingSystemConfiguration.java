/*
 * Copyright 2008-2013, 2016, 2017 by AO Industries, Inc.,
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
	REDHAT_ES_4_X86_64 {
		@Override
		public OperatingSystemConfiguration getOperatingSystemConfiguration() {
			return OperatingSystemConfiguration.REDHAT_ES_4_X86_64;
		}
		@Override
		public String getDefaultPhpMinorVersion() {
			return "5.2";
		}
		//public String getDefaultPhpPostgresMinorVersion() {
		//    return "8.1";
		//}
		@Override
		public String getPhpCgiPath(String minorVersion) {
			if(minorVersion.startsWith("5.")) return "/opt/php-5/bin/php-cgi";
			else throw new AssertionError("Unexpected PHP version: "+minorVersion);
		}
		@Override
		public String getHttpdSitesDirectory() {
			return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.REDHAT_ES_4_X86_64);
		}
		@Override
		public String getHttpdSharedTomcatsDirectory() {
			return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.REDHAT_ES_4_X86_64);
		}
		@Override
		public String getAwstatsDirectory() {
			return "/var/opt/awstats-6";
		}
		@Override
		public String getAwstatsBinDirectory() {
			return "/opt/awstats-6";
		}
	},
	CENTOS_5_I686_AND_X86_64 {
		@Override
		public OperatingSystemConfiguration getOperatingSystemConfiguration() {
			return OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64;
		}
		// TODO: Set to 5.5 - or better put in control panels and make be user selectable and changeable
		@Override
		public String getDefaultPhpMinorVersion() {
			return "5.6";
		}
		//public String getDefaultPhpPostgresMinorVersion() {
		//    return "9.2";
		//}
		@Override
		public String getPhpCgiPath(String minorVersion) {
			if(minorVersion.startsWith("4.")) return "/opt/php-4-i686/bin/php-cgi";
			else if(minorVersion.startsWith("5.")) return "/opt/php-" + minorVersion + "-i686/bin/php-cgi";
			else throw new AssertionError("Unexpected PHP version: "+minorVersion);
		}
		@Override
		public String getHttpdSitesDirectory() {
			return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
		}
		@Override
		public String getHttpdSharedTomcatsDirectory() {
			return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
		}
		@Override
		public String getAwstatsDirectory() {
			return "/var/opt/awstats-6";
		}
		@Override
		public String getAwstatsBinDirectory() {
			return "/opt/awstats-6";
		}
	},
	CENTOS_7_X86_64 {
		@Override
		public OperatingSystemConfiguration getOperatingSystemConfiguration() {
			return OperatingSystemConfiguration.CENTOS_7_X86_64;
		}
		// TODO: Set to 5.5 - or better put in control panels and make be user selectable and changeable
		@Override
		public String getDefaultPhpMinorVersion() {
			return "5.6";
		}
		//public String getDefaultPhpPostgresMinorVersion() {
		//    return "9.2";
		//}
		@Override
		public String getPhpCgiPath(String minorVersion) {
			if(minorVersion.startsWith("4.")) return "/opt/php-4/bin/php-cgi";
			else if(minorVersion.startsWith("5.")) return "/opt/php-" + minorVersion + "/bin/php-cgi";
			else throw new AssertionError("Unexpected PHP version: "+minorVersion);
		}
		@Override
		public String getHttpdSitesDirectory() {
			return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.CENTOS_7_X86_64);
		}
		@Override
		public String getHttpdSharedTomcatsDirectory() {
			return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.CENTOS_7_X86_64);
		}
		@Override
		public String getAwstatsDirectory() {
			return "/var/opt/awstats-6";
		}
		@Override
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
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.REDHAT_ES_4_X86_64 :
				return REDHAT_ES_4_X86_64;
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				return CENTOS_5_I686_AND_X86_64;
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Gets the configuration for general tasks.
	 */
	public abstract OperatingSystemConfiguration getOperatingSystemConfiguration();

	/**
	 * The default PHP version.
	 */
	public abstract String getDefaultPhpMinorVersion();

	/**
	 * The version of PostgreSQL minor version used by the default PHP minor version.
	 */
	//public abstract String getDefaultPhpPostgresMinorVersion();

	/**
	 * Gets the full path to the PHP CGI script for the provided PHP minor version.
	 */
	public abstract String getPhpCgiPath(String minorVersion);

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
