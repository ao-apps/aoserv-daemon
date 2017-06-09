/*
 * Copyright 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Some common code for Tomcat 8.0.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_8_0_X extends TomcatCommon {

	private static final TomcatCommon_8_0_X instance = new TomcatCommon_8_0_X();
	static TomcatCommon_8_0_X getInstance() {
		return instance;
	}

	private TomcatCommon_8_0_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return EnumSet.of(
			OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
			PackageManager.PackageName.APACHE_TOMCAT_8_0
		);
	}

	@Override
	public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException, IOException {
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
			out.print("            validationQuery=\"").encodeXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
		}
		out.print("            removeAbandoned=\"true\"\n"
				+ "            removeAbandonedTimeout=\"300\"\n"
				+ "            logAbandoned=\"true\"\n"
				+ "          />\n");
	}

	/**
	 * Upgrades the Tomcat 8.0.X installed in the provided directory.
	 *
	 * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
	 */
	boolean upgradeTomcatDirectory(String optSlash, UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(
			osConfig == OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64
			|| osConfig == OperatingSystemConfiguration.CENTOS_7_X86_64
		) {
			String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_8_0).getVersion().toString();
			if(rpmVersion.equals("8.0.30")) {
				// Nothing to do
			} else if(rpmVersion.equals("8.0.32")) {
				// Upgrade from Tomcat 8.0.30 to 8.0.32
				UpgradeSymlink[] upgradeSymlinks_8_0_32 = {
					new UpgradeSymlink(
						"lib/postgresql-9.4.1207.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/postgresql-9.4.1207.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-9.4.1208.jar",
						null,
						"../" + optSlash + "apache-tomcat-8.0/lib/postgresql-9.4.1208.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_32) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("8.0.43")) {
				 // Upgrade from Tomcat 8.0.32 to 8.0.43
				UpgradeSymlink[] upgradeSymlinks_8_0_43 = {
					// ecj-4.4.2.jar -> ecj-4.6.1.jar
					new UpgradeSymlink(
						"lib/ecj-4.4.2.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.4.2.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.6.1.jar",
						null,
						"../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar"
					),
					// ecj-4.6.1.jar.jar -> ecj-4.6.1.jar (unwhoops!)
					new UpgradeSymlink(
						"lib/ecj-4.6.1.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar"
					),
					// mysql-connector-java-5.1.38-bin.jar -> mysql-connector-java-5.1.42-bin.jar
					// mysql-connector-java-5.1.41-bin.jar -> mysql-connector-java-5.1.42-bin.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.38-bin.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.38-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.41-bin.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.41-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.42-bin.jar",
						null,
						"../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.42-bin.jar"
					),
					// postgresql-9.4.1208.jar -> postgresql-42.1.1.jar
					// postgresql-42.0.0.jar -> postgresql-42.1.1.jar
					new UpgradeSymlink(
						"lib/postgresql-9.4.1208.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/postgresql-9.4.1208.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.0.0.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.0.0.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.1.1.jar",
						null,
						"../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.1.1.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_43) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("8.0.44")) {
				 // Upgrade from Tomcat 8.0.43 to 8.0.44
				UpgradeSymlink[] upgradeSymlinks_8_0_44 = {
					// ecj-4.6.1.jar -> ecj-4.6.3.jar
					new UpgradeSymlink(
						"lib/ecj-4.6.1.jar",
						"../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.6.3.jar",
						null,
						"../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.3.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_44) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else {
				throw new IllegalStateException("Unexpected version of Tomcat: " + rpmVersion);
			}
		}
		return needsRestart;
	}
}
