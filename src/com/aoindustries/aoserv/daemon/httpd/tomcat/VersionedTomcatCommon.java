/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.CalendarUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/**
 * Some common code for all installations of Tomcat 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
public abstract class VersionedTomcatCommon extends TomcatCommon {

	private static final String BACKUP_DATE_SEPARATOR = "-";
	public static final String BACKUP_SEPARATOR = ".";
	public static final String BACKUP_EXTENSION = ".bak";

	/**
	 * Gets the suffix to put after an existing file, but before the extension.
	 */
	public static String getBackupSuffix() {
		return BACKUP_DATE_SEPARATOR + CalendarUtils.formatDate(new GregorianCalendar(Locale.ROOT));
	}

	VersionedTomcatCommon() {
	}

	/**
	 * See:
	 * <ol>
	 * <li>Tomcat 8.5:
	 *   <ol type="a">
	 *   <li><a href="https://tomcat.apache.org/tomcat-8.5-doc/jndi-datasource-examples-howto.html">JNDI Datasource HOW-TO</a></li>
	 *   <li><a href="https://tomcat.apache.org/tomcat-8.5-doc/jdbc-pool.html">The Tomcat JDBC Connection Pool</a></li>
	 *   </ol>
	 * </li>
	 * <li>Tomcat 9.0:
	 *   <ol type="a">
	 *   <li><a href="https://tomcat.apache.org/tomcat-9.0-doc/jndi-datasource-examples-howto.html">JNDI Datasource HOW-TO</a></li>
	 *   <li><a href="https://tomcat.apache.org/tomcat-9.0-doc/jdbc-pool.html">The Tomcat JDBC Connection Pool</a></li>
	 *   </ol>
	 * </li>
	 * </ol>
	 */
	@Override 
	public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws IOException, SQLException {
		out.print("          <Resource\n"
				+ "            name=\"").encodeXmlAttribute(dataSource.getName()).print("\"\n"
				+ "            auth=\"Container\"\n"
				+ "            type=\"javax.sql.DataSource\"\n"
				+ "            username=\"").encodeXmlAttribute(dataSource.getUsername()).print("\"\n"
				+ "            password=\"").encodeXmlAttribute(dataSource.getPassword()).print("\"\n"
				+ "            driverClassName=\"").encodeXmlAttribute(dataSource.getDriverClassName()).print("\"\n"
				+ "            url=\"").encodeXmlAttribute(dataSource.getUrl()).print("\"\n"
				+ "            maxActive=\"").encodeXmlAttribute(dataSource.getMaxActive()).print("\"\n"
				+ "            maxIdle=\"").encodeXmlAttribute(dataSource.getMaxIdle()).print("\"\n"
				+ "            maxWait=\"").encodeXmlAttribute(dataSource.getMaxWait()).print("\"\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            testWhileIdle=\"true\"\n"
					+ "            validationQuery=\"").encodeXmlAttribute(dataSource.getValidationQuery()).print("\"\n"
					+ "            validationQueryTimeout=\"30\"\n");
		}
		out.print("            removeAbandoned=\"true\"\n"
				+ "            removeAbandonedTimeout=\"300\"\n"
				+ "            logAbandoned=\"true\"\n"
				+ "          />\n");
	}

	protected static byte[] generateProfileCatalinaSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("export CATALINA_BASE=\"").print(installDir).print("\"\n"
					+ "export CATALINA_HOME=\"").print(installDir).print("\"\n"
					+ "export CATALINA_TEMP=\"").print(installDir).print("/temp\"\n"
					+ "\n"
					+ "export PATH=\"${PATH}:").print(installDir).print("/bin\"\n");
		}
		return bout.toByteArray();
	}

	protected static byte[] generateProfileJavaDisableUsageTrackingSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Djdk.disableLastUsageTracking=true\"\n");
		}
		return bout.toByteArray();
	}

	protected static byte[] generateProfileJavaHeadlessSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Djava.awt.headless=true\"\n");
		}
		return bout.toByteArray();
	}

	protected static byte[] generateProfileJavaHeapsizeSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Xmx128M\"\n");
		}
		return bout.toByteArray();
	}

	protected static byte[] generateProfileJavaServerSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-server\"\n");
		}
		return bout.toByteArray();
	}

	protected static String generateProfileJdkShTarget(String optSlash) throws IOException, SQLException {
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		String jdkProfileSh = osConfig.getDefaultJdkProfileSh().toString();
		if(!jdkProfileSh.startsWith("/opt/")) throw new IllegalStateException("jdkProfileSh does not start with \"/opt/\": " + jdkProfileSh);
		return "../../" + optSlash + jdkProfileSh.substring("/opt/".length());
	}

	protected static byte[] generateProfileUmaskSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("umask 002\n");
		}
		return bout.toByteArray();
	}

	protected static byte[] generateShutdownSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("#!/bin/sh\n"
					  + "exec \"").print(installDir).print("/bin/tomcat\" stop\n");
		}
		return bout.toByteArray();
	}

	protected static byte[] generateStartupSh(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("#!/bin/sh\n"
					  + "exec \"").print(installDir).print("/bin/tomcat\" start\n");
		}
		return bout.toByteArray();
	}

	/**
	 * Gets the name of the Tomcat directory under <code>/opt/</code>.
	 */
	protected abstract String getApacheTomcatDir();

	/**
	 * Gets the set of files that are installed during install and upgrade/downgrade.
	 * Each path is relative to CATALINA_HOME/CATALINA_BASE.
	 */
	protected abstract List<Install> getInstallFiles(String optSlash, UnixFile installDir, int confMode) throws IOException, SQLException;

	/**
	 * Upgrades the Tomcat installed in the provided directory.
	 *
	 * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
	 */
	protected abstract boolean upgradeTomcatDirectory(String optSlash, UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException;
}
