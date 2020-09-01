/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2018, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Copy;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Delete;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Generated;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Mkdir;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.ProfileScript;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Symlink;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.SymlinkAll;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Some common code for Tomcat 9.0.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_9_0_X extends VersionedTomcatCommon {

	private static final TomcatCommon_9_0_X instance = new TomcatCommon_9_0_X();
	static TomcatCommon_9_0_X getInstance() {
		return instance;
	}

	private TomcatCommon_9_0_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return EnumSet.of(
			OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
			PackageManager.PackageName.AOSERV_PROFILE_D,
			PackageManager.PackageName.APACHE_TOMCAT_9_0
		);
	}

	@Override
	protected String getApacheTomcatDir() {
		return "apache-tomcat-9.0";
	}

	@Override
	protected List<Install> getInstallFiles(String optSlash, UnixFile installDir, int confMode) throws IOException, SQLException {
		return Arrays.asList(
			new Mkdir        ("bin", 0770),
			new Symlink      ("bin/bootstrap.jar"),
			new ProfileScript("bin/catalina.sh"),
			new ProfileScript("bin/ciphers.sh"), // Tomcat 8.5+
			new ProfileScript("bin/configtest.sh"),
			new Symlink      ("bin/commons-daemon.jar"),
			new Delete       ("bin/commons-logging-api.jar"), // Tomcat 5.5
			// Skipped bin/daemon.sh
			new ProfileScript("bin/digest.sh"),
			new Delete       ("bin/jasper.sh"), // Tomcat 4.1
			new Delete       ("bin/jspc.sh"), // Tomcat 4.1
			// Skipped bin/makebase.sh
			new Delete       ("bin/profile"), // Tomcat 4.1, Tomcat 5.5, Tomcat 6.0, Tomcat 7.0, Tomcat 8.0
			new Mkdir        ("bin/profile.d", 0750),
			new Generated    ("bin/profile.d/catalina.sh",                    0640, VersionedTomcatCommon::generateProfileCatalinaSh),
			new Generated    ("bin/profile.d/java-disable-usage-tracking.sh", 0640, VersionedTomcatCommon::generateProfileJavaDisableUsageTrackingSh),
			new Generated    ("bin/profile.d/java-headless.sh",               0640, VersionedTomcatCommon::generateProfileJavaHeadlessSh),
			new Generated    ("bin/profile.d/java-heapsize.sh",               0640, VersionedTomcatCommon::generateProfileJavaHeapsizeSh),
			new Generated    ("bin/profile.d/java-server.sh",                 0640, VersionedTomcatCommon::generateProfileJavaServerSh),
			new Symlink      ("bin/profile.d/jdk.sh", generateProfileJdkShTarget(optSlash)),
			new Generated    ("bin/profile.d/umask.sh",                       0640, VersionedTomcatCommon::generateProfileUmaskSh),
			new Symlink      ("bin/setclasspath.sh"),
			new Generated    ("bin/shutdown.sh", 0700, VersionedTomcatCommon::generateShutdownSh),
			new Generated    ("bin/startup.sh",  0700, VersionedTomcatCommon::generateStartupSh),
			new Delete       ("bin/tomcat-jni.jar"), // Tomcat 4.1
			new Symlink      ("bin/tomcat-juli.jar"),
			new Symlink      ("bin/tool-wrapper.sh"),
			new ProfileScript("bin/version.sh"),
			new Delete       ("common"), // Tomcat 4.1, Tomcat 5.5
			new Mkdir        ("conf", confMode),
			new Mkdir        ("conf/Catalina", 0770),
			new Symlink      ("conf/catalina.policy"),
			new Symlink      ("conf/catalina.properties"),
			new Symlink      ("conf/context.xml"),
			new Symlink      ("conf/jaspic-providers.xml"),
			new Symlink      ("conf/jaspic-providers.xsd"),
			new Symlink      ("conf/logging.properties"),
			new Delete       ("conf/server.xml"), // Backup any existing, new will be created below to handle both auto and manual modes
			new Copy         ("conf/tomcat-users.xml", 0660),
			new Symlink      ("conf/tomcat-users.xsd"),
			new Symlink      ("conf/web.xml"),
			new Mkdir        ("daemon", 0770),
			new Mkdir        ("lib", 0770),
			new SymlinkAll   ("lib"),
			new Symlink      ("logs", "var/log"),
			new Delete       ("RELEASE-NOTES"), // Backup any existing, new will be created to detect version updates that do not change symlinks
			new Delete       ("shared"), // Tomcat 4.1, Tomcat 5.5
			new Delete       ("server"), // Tomcat 4.1, Tomcat 5.5
			new Mkdir        ("temp", 0770),
			new Mkdir        ("var", 0770),
			new Mkdir        ("var/log", 0770),
			new Mkdir        ("var/run", 0770),
			new Mkdir        ("work", 0750),
			new Mkdir        ("work/Catalina", 0750),
			new Delete       ("conf/Tomcat-Apache") // Tomcat 4.1, Tomcat 5.5
		);
	}

	/**
	 * Upgrades the Tomcat 9.0.X installed in the provided directory.
	 *
	 * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
	 */
	@Override
	protected boolean upgradeTomcatDirectory(String optSlash, UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		// TODO: This might be able to simply use the same lnAll as used to initially create the lib/ directory
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(osConfig == OperatingSystemConfiguration.CENTOS_7_X86_64) {
			String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_9_0).getVersion().toString();
			if(rpmVersion.equals("9.0.10")) {
				// Nothing to do
			} else if(
				rpmVersion.equals("9.0.11")
				|| rpmVersion.equals("9.0.12")
				|| rpmVersion.equals("9.0.13")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_11 = {
					// mysql-connector-java-8.0.12.jar -> mysql-connector-java-8.0.13.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.12.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.12.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.13.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.13.jar"
					),
					// New lib/tomcat-i18n-ru.jar
					new UpgradeSymlink(
						"lib/tomcat-i18n-ru.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-ru.jar"
					),
					// postgresql-42.2.4.jar -> postgresql-42.2.5.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.4.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.4.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.5.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.5.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_11) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("9.0.14")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_14 = {
					// ecj-4.7.3a.jar -> ecj-4.9.jar
					new UpgradeSymlink(
						"lib/ecj-4.7.3a.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.7.3a.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.9.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.9.jar"
					),
					// mysql-connector-java-8.0.13.jar -> mysql-connector-java-8.0.15.jar
					// mysql-connector-java-8.0.14.jar -> mysql-connector-java-8.0.15.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.13.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.13.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.14.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.14.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.15.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.15.jar"
					),
					// New lib/tomcat-i18n-*.jar
					new UpgradeSymlink(
						"lib/tomcat-i18n-de.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-de.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat-i18n-ko.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-ko.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat-i18n-pt-BR.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-pt-BR.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat-i18n-zh-CN.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-zh-CN.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_14) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("9.0.16")
				|| rpmVersion.equals("9.0.17")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_16 = {
					// ecj-4.7.3a.jar -> ecj-4.9.jar
					new UpgradeSymlink(
						"lib/ecj-4.7.3a.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.7.3a.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.9.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.9.jar"
					),
					// mysql-connector-java-8.0.13.jar -> mysql-connector-java-8.0.15.jar
					// mysql-connector-java-8.0.14.jar -> mysql-connector-java-8.0.15.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.13.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.13.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.14.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.14.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.15.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.15.jar"
					),
					// New lib/tomcat-i18n-*.jar
					new UpgradeSymlink(
						"lib/tomcat-i18n-cs.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-cs.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat-i18n-de.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-de.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat-i18n-ko.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-ko.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat-i18n-pt-BR.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-pt-BR.jar"
					),
					new UpgradeSymlink(
						"lib/tomcat-i18n-zh-CN.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/tomcat-i18n-zh-CN.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_16) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("9.0.19")
				|| rpmVersion.equals("9.0.20")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_19 = {
					// ecj-4.9.jar -> ecj-4.10.jar
					new UpgradeSymlink(
						"lib/ecj-4.9.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.9.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.10.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.10.jar"
					),
					// mysql-connector-java-8.0.15.jar -> mysql-connector-java-8.0.16.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.15.jar",
						"/dev/null",
						"lib/mysql-connector-java-8.0.16.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.15.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.15.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.16.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.16.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_19) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("9.0.21")) {
				UpgradeSymlink[] upgradeSymlinks_9_0_21 = {
					// postgresql-42.2.5.jar -> postgresql-42.2.6.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.5.jar",
						"/dev/null",
						"lib/postgresql-42.2.6.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.5.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.5.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.6.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.6.jar"
					)
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_21) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("9.0.22")
				|| rpmVersion.equals("9.0.24")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_22 = {
					// ecj-4.10.jar -> ecj-4.12.jar
					new UpgradeSymlink(
						"lib/ecj-4.10.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.10.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.12.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.12.jar"
					),
					// mysql-connector-java-8.0.16.jar -> mysql-connector-java-8.0.17.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.16.jar",
						"/dev/null",
						"lib/mysql-connector-java-8.0.17.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.16.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.16.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.17.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.17.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_22) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("9.0.26")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_26 = {
					// postgresql-42.2.6.jar -> postgresql-42.2.8.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.6.jar",
						"/dev/null",
						"lib/postgresql-42.2.8.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.6.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.8.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.8.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_26) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("9.0.27")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_27 = {
					// postgresql-42.2.6.jar -> postgresql-42.2.8.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.6.jar",
						"/dev/null",
						"lib/postgresql-42.2.8.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.6.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.6.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.8.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.8.jar"
					),
					// ecj-4.12.jar -> ecj-4.13.jar
					new UpgradeSymlink(
						"lib/ecj-4.12.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.12.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.13.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.13.jar"
					),
					// mysql-connector-java-8.0.17.jar -> mysql-connector-java-8.0.18.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.17.jar",
						"/dev/null",
						"lib/mysql-connector-java-8.0.18.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.17.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.17.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.18.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.18.jar"
					),
					// postgresql-42.2.8.jar -> postgresql-42.2.9.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.8.jar",
						"/dev/null",
						"lib/postgresql-42.2.9.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.8.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.8.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.9.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.9.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_27) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("9.0.30")) {
				UpgradeSymlink[] upgradeSymlinks_9_0_30 = {
					// New lib/catalina-ssi.jar
					new UpgradeSymlink(
						"lib/catalina-ssi.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/catalina-ssi.jar"
					),
					// mysql-connector-java-8.0.18.jar -> mysql-connector-java-8.0.19.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.18.jar",
						"/dev/null",
						"lib/mysql-connector-java-8.0.19.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.18.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.18.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.19.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.19.jar"
					),
					// postgresql-42.2.8.jar -> postgresql-42.2.9.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.8.jar",
						"/dev/null",
						"lib/postgresql-42.2.9.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.8.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.8.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.9.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.9.jar"
					),
					// postgresql-42.2.9.jar -> postgresql-42.2.10.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.9.jar",
						"/dev/null",
						"lib/postgresql-42.2.10.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.9.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.9.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.10.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.10.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_30) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(
				rpmVersion.equals("9.0.31")
				|| rpmVersion.equals("9.0.33")
				|| rpmVersion.equals("9.0.34")
				|| rpmVersion.equals("9.0.35")
			) {
				UpgradeSymlink[] upgradeSymlinks_9_0_31 = {
					// postgresql-42.2.10.jar -> postgresql-42.2.11.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.10.jar",
						"/dev/null",
						"lib/postgresql-42.2.11.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.10.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.10.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.11.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.11.jar"
					),
					// postgresql-42.2.11.jar -> postgresql-42.2.12.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.11.jar",
						"/dev/null",
						"lib/postgresql-42.2.12.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.11.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.11.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.12.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.12.jar"
					),
					// ecj-4.13.jar -> ecj-4.15.jar
					new UpgradeSymlink(
						"lib/ecj-4.13.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.13.jar",
						null
					),
					new UpgradeSymlink(
						"lib/ecj-4.15.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/ecj-4.15.jar"
					),
					// mysql-connector-java-8.0.19.jar -> mysql-connector-java-8.0.20.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.19.jar",
						"/dev/null",
						"lib/mysql-connector-java-8.0.20.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.19.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.19.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.20.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.20.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_31) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("9.0.36")) {
				UpgradeSymlink[] upgradeSymlinks_9_0_36 = {
					// postgresql-42.2.12.jar -> postgresql-42.2.13.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.12.jar",
						"/dev/null",
						"lib/postgresql-42.2.13.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.12.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.12.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.13.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.13.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_36) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("9.0.37")) {
				UpgradeSymlink[] upgradeSymlinks_9_0_37 = {
					// postgresql-42.2.13.jar -> postgresql-42.2.14.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.13.jar",
						"/dev/null",
						"lib/postgresql-42.2.14.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.13.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.13.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.14.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.14.jar"
					),
					// mysql-connector-java-8.0.20.jar -> mysql-connector-java-8.0.21.jar
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.20.jar",
						"/dev/null",
						"lib/mysql-connector-java-8.0.21.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.20.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.20.jar",
						null
					),
					new UpgradeSymlink(
						"lib/mysql-connector-java-8.0.21.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/mysql-connector-java-8.0.21.jar"
					),
					// postgresql-42.2.14.jar -> postgresql-42.2.16.jar
					new UpgradeSymlink(
						"lib/postgresql-42.2.14.jar",
						"/dev/null",
						"lib/postgresql-42.2.16.jar",
						"/dev/null"
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.14.jar",
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.14.jar",
						null
					),
					new UpgradeSymlink(
						"lib/postgresql-42.2.16.jar",
						null,
						"../" + optSlash + "apache-tomcat-9.0/lib/postgresql-42.2.16.jar"
					),
				};
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_9_0_37) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else {
				throw new IllegalStateException("Unexpected version of Tomcat: " + rpmVersion);
			}
		}
		return needsRestart;
	}
}
