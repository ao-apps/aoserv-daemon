/*
 * Copyright 2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.AOServDaemon;
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
 * Some common code for Tomcat 7.0.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_7_0_X extends TomcatCommon {

	private static final TomcatCommon_7_0_X instance = new TomcatCommon_7_0_X();
	static TomcatCommon_7_0_X getInstance() {
		return instance;
	}

	private TomcatCommon_7_0_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return EnumSet.of(
			OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
			PackageManager.PackageName.APACHE_TOMCAT_7_0
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
	 * Upgrades the Tomcat 7.0.X installed in the provided directory.
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
			String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_7_0).getVersion().toString();
			if(rpmVersion.equals("7.0.42")) {
				// Nothing to do
			} else if(rpmVersion.equals("7.0.68")) {
				UpgradeSymlink[] upgradeSymlinks_7_0_68 = {
					new UpgradeSymlink(
						"lib/ecj-4.2.2.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/ecj-4.2.2.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.4.2.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/ecj-4.4.2.jar"
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.25-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.25-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.38-bin.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.38-bin.jar"
					),
					new UpgradeSymlink(
						"lib/postgresql-9.2-1003.jdbc4.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-9.2-1003.jdbc4.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-9.4.1208.jre6.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-9.4.1208.jre6.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat7-websocket.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/tomcat7-websocket.jar"
					),
					new UpgradeSymlink(
						"lib/websocket-api.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/websocket-api.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_68) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
				if(osConfig == OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
					// Switch from Java 1.7 to Java 1.* now that compatible with Java 1.8
					String results = AOServDaemon.execAndCapture(
						new String[] {
							osConfig.getReplaceCommand().toString(),
							"/opt/jdk1.7-i686/setenv.sh",
							"/opt/jdk1-i686/setenv.sh",
							"--",
							tomcatDirectory.getPath()+"/bin/profile"
						}
					);
					if(results.length()>0) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("7.0.77")
				|| rpmVersion.equals("7.0.78")
				|| rpmVersion.equals("7.0.79")
				|| rpmVersion.equals("7.0.81")
				|| rpmVersion.equals("7.0.82")
			) {
				UpgradeSymlink[] upgradeSymlinks_7_0_77 = {
					// mysql-connector-java-5.1.38-bin.jar -> mysql-connector-java-5.1.45-bin.jar
					// mysql-connector-java-5.1.41-bin.jar -> mysql-connector-java-5.1.45-bin.jar
					// mysql-connector-java-5.1.42-bin.jar -> mysql-connector-java-5.1.45-bin.jar
					// mysql-connector-java-5.1.43-bin.jar -> mysql-connector-java-5.1.45-bin.jar
					// mysql-connector-java-5.1.44-bin.jar -> mysql-connector-java-5.1.45-bin.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.38-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.38-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.41-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.41-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.42-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.42-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.43-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.43-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.44-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.44-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.45-bin.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.45-bin.jar"
					),
					// postgresql-9.4.1208.jre6.jar -> postgresql-42.2.0.jre6.jar
					// postgresql-42.0.0.jre6.jar -> postgresql-42.2.0.jre6.jar
					// postgresql-42.1.1.jre6.jar -> postgresql-42.2.0.jre6.jar
					// postgresql-42.1.4.jre6.jar -> postgresql-42.2.0.jre6.jar
					new UpgradeSymlink(
						"lib/postgresql-9.4.1208.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-9.4.1208.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.0.0.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.0.0.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.1.1.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.1.1.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.1.4.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.1.4.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.0.jre6.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.0.jre6.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_77) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("7.0.84")
				|| rpmVersion.equals("7.0.85")
				|| rpmVersion.equals("7.0.86")
				|| rpmVersion.equals("7.0.88")
			) {
				UpgradeSymlink[] upgradeSymlinks_7_0_84 = {
					// mysql-connector-java-5.1.45-bin.jar -> mysql-connector-java-5.1.46-bin.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.45-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.45-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.46-bin.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.46-bin.jar"
					),
					// postgresql-42.2.0.jre6.jar -> postgresql-42.2.2.jre6.jar
					// postgresql-42.2.1.jre6.jar -> postgresql-42.2.1.jre6.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.0.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.0.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.1.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.1.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.2.jre6.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.2.jre6.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_84) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("7.0.90")
			) {
				UpgradeSymlink[] upgradeSymlinks_7_0_90 = {
					// mysql-connector-java-5.1.46-bin.jar -> mysql-connector-java-5.1.47-bin.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.46-bin.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.46-bin.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-5.1.47-bin.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.47-bin.jar"
					),
					// postgresql-42.2.2.jre6.jar -> postgresql-42.2.5.jre6.jar
					// postgresql-42.2.4.jre6.jar -> postgresql-42.2.5.jre6.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.2.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.2.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.4.jre6.jar",
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.4.jre6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.5.jre6.jar",
						null,
						"../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.5.jre6.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_90) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else {
				throw new IllegalStateException("Unexpected version of Tomcat: " + rpmVersion);
			}
		}
		return needsRestart;
	}
}
