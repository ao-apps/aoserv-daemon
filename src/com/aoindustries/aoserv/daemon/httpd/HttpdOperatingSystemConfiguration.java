/*
 * Copyright 2008-2013, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.util.WrappedException;
import com.aoindustries.validation.ValidationException;
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
		//public String getDefaultPhpPostgresMinorVersion() {
		//    return "8.1";
		//}
		@Override
		public UnixPath getPhpCgiPath(String minorVersion) {
			try {
				if(minorVersion.startsWith("5.")) return UnixPath.valueOf("/opt/php-5/bin/php-cgi");
				else throw new AssertionError("Unexpected PHP version: "+minorVersion);
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public UnixPath getHttpdSitesDirectory() {
			return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.REDHAT_ES_4_X86_64);
		}
		@Override
		public String getHttpdSitesOptSlash() {
			return "../../opt/";
		}
		@Override
		public UnixPath getHttpdSharedTomcatsDirectory() {
			return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.REDHAT_ES_4_X86_64);
		}
		@Override
		public String getHttpdSharedTomcatsOptSlash() {
			return "../../opt/";
		}
		@Override
		public UnixPath getAwstatsDirectory() {
			try {
				return UnixPath.valueOf("/var/opt/awstats-6");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public UnixPath getAwstatsBinDirectory() {
			try {
				return UnixPath.valueOf("/opt/awstats-6");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
	},
	CENTOS_5_I686_AND_X86_64 {
		@Override
		public OperatingSystemConfiguration getOperatingSystemConfiguration() {
			return OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64;
		}
		//public String getDefaultPhpPostgresMinorVersion() {
		//    return "9.2";
		//}
		@Override
		public UnixPath getPhpCgiPath(String minorVersion) {
			try {
				if(minorVersion.startsWith("4.")) return UnixPath.valueOf("/opt/php-4-i686/bin/php-cgi");
				else if(minorVersion.startsWith("5.")) return UnixPath.valueOf("/opt/php-" + minorVersion + "-i686/bin/php-cgi");
				else throw new AssertionError("Unexpected PHP version: "+minorVersion);
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public UnixPath getHttpdSitesDirectory() {
			return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
		}
		@Override
		public String getHttpdSitesOptSlash() {
			return "../../opt/";
		}
		@Override
		public UnixPath getHttpdSharedTomcatsDirectory() {
			return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
		}
		@Override
		public String getHttpdSharedTomcatsOptSlash() {
			return "../../opt/";
		}
		@Override
		public UnixPath getAwstatsDirectory() {
			try {
				return UnixPath.valueOf("/var/opt/awstats-6");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public UnixPath getAwstatsBinDirectory() {
			try {
				return UnixPath.valueOf("/opt/awstats-6");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
	},
	CENTOS_7_X86_64 {
		@Override
		public OperatingSystemConfiguration getOperatingSystemConfiguration() {
			return OperatingSystemConfiguration.CENTOS_7_X86_64;
		}
		//public String getDefaultPhpPostgresMinorVersion() {
		//    return "9.2";
		//}
		@Override
		public UnixPath getPhpCgiPath(String minorVersion) {
			try {
				if(minorVersion.startsWith("5.")) return UnixPath.valueOf("/opt/php-" + minorVersion + "/bin/php-cgi");
				else throw new AssertionError("Unexpected PHP version: "+minorVersion);
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public UnixPath getHttpdSitesDirectory() {
			return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.CENTOS_7_X86_64);
		}
		@Override
		public String getHttpdSitesOptSlash() {
			return "../../../opt/";
		}
		@Override
		public UnixPath getHttpdSharedTomcatsDirectory() {
			return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.CENTOS_7_X86_64);
		}
		@Override
		public String getHttpdSharedTomcatsOptSlash() {
			return "../../../../opt/";
		}
		@Override
		public UnixPath getAwstatsDirectory() {
			try {
				return UnixPath.valueOf("/var/opt/awstats-6");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public UnixPath getAwstatsBinDirectory() {
			try {
				return UnixPath.valueOf("/opt/awstats-6");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
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
			case OperatingSystemVersion.CENTOS_7_X86_64:
				return CENTOS_7_X86_64;
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Gets the configuration for general tasks.
	 */
	public abstract OperatingSystemConfiguration getOperatingSystemConfiguration();

	/**
	 * The version of PostgreSQL minor version used by the default PHP minor version.
	 */
	//public abstract String getDefaultPhpPostgresMinorVersion();

	/**
	 * Gets the full path to the PHP CGI script for the provided PHP minor version.
	 */
	public abstract UnixPath getPhpCgiPath(String minorVersion);

	/**
	 * Gets the directory that contains the website directories.
	 */
	public abstract UnixPath getHttpdSitesDirectory();

	/**
	 * Gets the relative path from CATALINA_HOME to /opt/ for the website directories, including the trailing slash.
	 */
	public abstract String getHttpdSitesOptSlash();

	/**
	 * Gets the directory that contains the shared tomcat directories.
	 */
	public abstract UnixPath getHttpdSharedTomcatsDirectory();

	/**
	 * Gets the relative path from CATALINA_HOME to /opt/ for the shared tomcat directories, including the trailing slash.
	 */
	public abstract String getHttpdSharedTomcatsOptSlash();

	/**
	 * Gets the configuration directory used by AWStats
	 */
	public UnixPath getAwstatsConfigDirectory() {
		try {
			return UnixPath.valueOf("/etc/awstats");
		} catch(ValidationException e) {
			throw new WrappedException(e);
		}
	}

	/**
	 * Gets the main AWStats directory.
	 */
	public abstract UnixPath getAwstatsDirectory();

	/**
	 * Gets the AWStats hosts directory.
	 */
	public UnixPath getAwstatsHostsDirectory() {
		try {
			return UnixPath.valueOf(getAwstatsDirectory()+"/hosts");
		} catch(ValidationException e) {
			throw new WrappedException(e);
		}
	}

	/**
	 * Gets the AWStats bin directory.
	 */
	public abstract UnixPath getAwstatsBinDirectory();

	/**
	 * Gets the AWStats icon directory.
	 */
	public UnixPath getAwstatsIconDirectory() {
		try {
			return UnixPath.valueOf(getAwstatsBinDirectory()+"/wwwroot/icon");
		} catch(ValidationException e) {
			throw new WrappedException(e);
		}
	}
}
