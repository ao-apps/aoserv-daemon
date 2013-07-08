/*
 * Copyright 2008-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Operating system-specific configurations.
 *
 * @author  AO Industries, Inc.
 */
public enum OperatingSystemConfiguration {
	REDHAT_ES_4_X86_64 {
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return HttpdOperatingSystemConfiguration.REDHAT_ES_4_X86_64;
		}
		public String getDefaultJdkVersion() {
			return "1.5.0";
		}
		public String getOpensslDefaultCaFile() {
			return "/etc/ssl/CA/ca.txt";
		}
		public String getReplaceCommand() {
			return "/opt/mysql-5.0/bin/replace";
		}
		public String getScriptInclude(String script) {
			return "/opt/aoserv-client/scripts/"+script;
		}
		public String getAOServClientScriptInclude() {
			return "/opt/aoserv-client/scripts/aoserv-client.sh";
		}
		public String getPostgresPath(String minorVersion) {
			return "/opt/postgresql-"+minorVersion;
		}
		public String getMySQLConnectorJavaJarPath() {
			return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
		}
	},
	CENTOS_5_I686_AND_X86_64 {
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64;
		}
		public String getDefaultJdkVersion() {
			return "1";
		}
		public String getOpensslDefaultCaFile() {
			return "/etc/pki/tls/certs/ca-bundle.crt";
		}
		public String getReplaceCommand() {
			return "/opt/aoserv-daemon/bin/replace";
		}
		public String getScriptInclude(String script) {
			return "/opt/aoserv-client/scripts/"+script;
		}
		public String getAOServClientScriptInclude() {
			return "/opt/aoserv-client/scripts/aoserv-client.sh";
		}
		public String getPostgresPath(String minorVersion) {
			return "/opt/postgresql-"+minorVersion+"-i686";
		}
		public String getMySQLConnectorJavaJarPath() {
			return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
		}
	},
	CENTOS_5DOM0_I686 {
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return null;
		}
		public String getDefaultJdkVersion() {
			return "1.5.0";
		}
		public String getOpensslDefaultCaFile() {
			return "/etc/pki/tls/certs/ca-bundle.crt";
		}
		public String getReplaceCommand() {
			return null;
		}
		public String getScriptInclude(String script) {
			return "/opt/aoserv-client/scripts/"+script;
		}
		public String getAOServClientScriptInclude() {
			return "/opt/aoserv-client/scripts/aoserv-client.sh";
		}
		public String getPostgresPath(String minorVersion) {
			return null;
		}
		public String getMySQLConnectorJavaJarPath() {
			return null;
		}
	},
	CENTOS_5DOM0_X86_64 {
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return null;
		}
		public String getDefaultJdkVersion() {
			return "1.5.0";
		}
		public String getOpensslDefaultCaFile() {
			return "/etc/pki/tls/certs/ca-bundle.crt";
		}
		public String getReplaceCommand() {
			return null;
		}
		public String getScriptInclude(String script) {
			return "/opt/aoserv-client/scripts/"+script;
		}
		public String getAOServClientScriptInclude() {
			return "/opt/aoserv-client/scripts/aoserv-client.sh";
		}
		public String getPostgresPath(String minorVersion) {
			return null;
		}
		public String getMySQLConnectorJavaJarPath() {
			return null;
		}
	};

	/**
	 * Gets the operating system configuration for this server, as
	 * determined by the <code>AOServDaemon.getThisServer()</code>
	 * method.
	 *
	 * @see  AOServDaemon#getThisServer()
	 */
	public static OperatingSystemConfiguration getOperatingSystemConfiguration() throws IOException, SQLException {
		int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
		switch(osv) {
			case OperatingSystemVersion.REDHAT_ES_4_X86_64 :
				return REDHAT_ES_4_X86_64;
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				return CENTOS_5_I686_AND_X86_64;
			case OperatingSystemVersion.CENTOS_5_DOM0_I686 :
				return CENTOS_5DOM0_I686;
			case OperatingSystemVersion.CENTOS_5_DOM0_X86_64 :
				return CENTOS_5DOM0_X86_64;
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
		}
	}

	/**
	 * Gets the configuration for HTTP-related tasks or <code>null</code>
	 * if this OS version doesn't have any HTTP servers.
	 */
	public abstract HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration();

	/**
	 * The default JDK version, not including any "jdk".
	 */
	public abstract String getDefaultJdkVersion();

	/**
	 * Gets the default certificate authority file for OpenSSL.
	 */
	public abstract String getOpensslDefaultCaFile();

	/**
	 * Gets the full path to the replace command or <code>null</code>
	 * if unavailable.
	 */
	public abstract String getReplaceCommand();

	/**
	 * Gets the full path that a shell script would use to include the provided
	 * profile script.
	 */
	public abstract String getScriptInclude(String script);

	/**
	 * Gets the full path that a shell script would use to include the AOServ
	 * Client.
	 */
	public abstract String getAOServClientScriptInclude();

	/**
	 * Gets the path to the PostgreSQL installation directory for the provided
	 * minor version (7.3, 8.1, 8.3, ...).
	 *
	 * @return  The full path or <code>null</code> if PostgreSQL not supported.
	 */
	public abstract String getPostgresPath(String minorVersion);

	/**
	 * Gets the full path to the preferred MySQL Java JDBC driver JAR or
	 * <code>null</code> if none available.
	 */
	public abstract String getMySQLConnectorJavaJarPath();
}
