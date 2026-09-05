/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2022, 2023, 2024, 2025, 2026  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Copy;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Delete;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Generated;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Mkdir;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.ProfileScript;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Symlink;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.SymlinkAll;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Some common code for Tomcat 10.1.X
 *
 * @author  AO Industries, Inc.
 */
final class TomcatCommon_10_1_X extends VersionedTomcatCommon {

  private static final TomcatCommon_10_1_X instance = new TomcatCommon_10_1_X();

  static TomcatCommon_10_1_X getInstance() {
    return instance;
  }

  private TomcatCommon_10_1_X() {
    // Do nothing
  }

  @Override
  protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
    return EnumSet.of(
        OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
        PackageManager.PackageName.AOSERV_PROFILE_D,
        PackageManager.PackageName.APACHE_TOMCAT_10_1
    );
  }

  @Override
  protected String getApacheTomcatDir() {
    return "apache-tomcat-10.1";
  }

  // Note: Updates here should be matched between all VersionTomcat versions (8.5, 9.0, 10.0, 10.1 currently)
  //       in order to allow both upgrade and downgrade between them.
  @Override
  protected List<Install> getInstallFiles(String optSlash, PosixFile installDir, int confMode) throws IOException, SQLException {
    return Arrays.asList(
        new Mkdir        ("bin", 0770),
        new Symlink      ("bin/bootstrap.jar"),
        // Skipped bin/catalina.bat
        new ProfileScript("bin/catalina.sh"),
        // Skipped bin/catalina-tasks.xml
        // Skipped bin/ciphers.bat in Tomcat 8.5+
        new ProfileScript("bin/ciphers.sh"), // Tomcat 8.5+
        new Symlink      ("bin/commons-daemon.jar"),
        // Skipped bin/commons-daemon-native.tar.gz
        new Delete       ("bin/commons-logging-api.jar"), // Tomcat 5.5
        // Skipped bin/configtest.bat
        new ProfileScript("bin/configtest.sh"),
        // Skipped bin/daemon.sh in Tomcat 8.5+
        // Skipped bin/digest.bat
        new ProfileScript("bin/digest.sh"),
        new Delete       ("bin/jasper.sh"), // Tomcat 4.1
        new Delete       ("bin/jspc.sh"), // Tomcat 4.1
        // Skipped bin/makebase.bat in Tomcat 9.0+
        // Skipped bin/makebase.sh in Tomcat 9.0+
        // Skipped bin/migrate.bat in Tomcat 10.0+
        new ProfileScript("bin/migrate.sh"), // Tomcat 10.0+
        new Delete       ("bin/profile"), // Tomcat 4.1, Tomcat 5.5, Tomcat 6.0, Tomcat 7.0, Tomcat 8.0
        new Mkdir        ("bin/profile.d", 0750),
        new Generated    ("bin/profile.d/catalina.sh",                    0640, VersionedTomcatCommon::generateProfileCatalinaSh),
        new Generated    ("bin/profile.d/java-disable-usage-tracking.sh", 0640, VersionedTomcatCommon::generateProfileJavaDisableUsageTrackingSh),
        new Generated    ("bin/profile.d/java-headless.sh",               0640, VersionedTomcatCommon::generateProfileJavaHeadlessSh),
        new Generated    ("bin/profile.d/java-heapsize.sh",               0640, VersionedTomcatCommon::generateProfileJavaHeapsizeSh),
        new Generated    ("bin/profile.d/java-server.sh",                 0640, VersionedTomcatCommon::generateProfileJavaServerSh),
        new Symlink      ("bin/profile.d/jdk.sh", generateProfileJdkShTarget(optSlash)),
        new Generated    ("bin/profile.d/umask.sh",                       0640, VersionedTomcatCommon::generateProfileUmaskSh),
        // Skipped bin/setclasspath.bat
        new Symlink      ("bin/setclasspath.sh"),
        // Skipped bin/shutdown.bat
        new Generated    ("bin/shutdown.sh", 0700, VersionedTomcatCommon::generateShutdownSh),
        // Skipped bin/startup.bat
        new Generated    ("bin/startup.sh",  0700, VersionedTomcatCommon::generateStartupSh),
        new Delete       ("bin/tomcat-jni.jar"), // Tomcat 4.1
        new Symlink      ("bin/tomcat-juli.jar"),
        // Skipped bin/tomcat-native.tar.gz
        // Skipped bin/tool-wrapper.bat
        new Symlink      ("bin/tool-wrapper.sh"),
        // Skipped bin/version.bat
        new ProfileScript("bin/version.sh"),
        // Skipped BUILDING.txt
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
        // Skipped CONTRIBUTING.md
        new Mkdir        ("daemon", 0770),
        new Mkdir        ("lib", 0770),
        new SymlinkAll   ("lib"),
        // Skipped LICENSE
        new Symlink      ("logs", "var/log"),
        // Skipped NOTICE in Tomcat 8.5 to 9.0
        // Skipped README.md
        new Delete       ("RELEASE-NOTES"), // Backup any existing, new will be created to detect version updates that do not change symlinks
        // Skipped RUNNING.txt
        new Delete       ("shared"), // Tomcat 4.1, Tomcat 5.5
        new Delete       ("server"), // Tomcat 4.1, Tomcat 5.5
        new Mkdir        ("temp", 0770),
        new Mkdir        ("var", 0770),
        new Mkdir        ("var/log", 0770),
        new Mkdir        ("var/run", 0770),
        // Skipped webapps (is handled elsewhere)
        // Skipped webapps_docs.tgz
        // Skipped webapps_examples.tgz
        new Mkdir        ("work", 0750),
        new Mkdir        ("work/Catalina", 0750),
        new Delete       ("conf/Tomcat-Apache") // Tomcat 4.1, Tomcat 5.5
    );
  }

  /**
   * Upgrades the Tomcat 10.1.X installed in the provided directory.
   *
   * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
   */
  @Override
  @SuppressWarnings("UnusedAssignment")
  protected boolean upgradeTomcatDirectory(String optSlash, PosixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
    // TODO: This might be able to simply use the same lnAll as used to initially create the lib/ directory
    boolean needsRestart = false;
    OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    if (osConfig == OperatingSystemConfiguration.CENTOS_7_X86_64
        || osConfig == OperatingSystemConfiguration.ROCKY_9_X86_64) {
      final Version rpmVersion = getRpmVersion(PackageManager.PackageName.APACHE_TOMCAT_10_1,
          PackageManager.PackageName.OLD_APACHE_TOMCAT_10_1);
      final String suffix = osConfig.getPackageReleaseSuffix();
      final String oldSuffix = osConfig.getOldPackageReleaseSuffix();

      // Version history
      Map<String, List<UpgradeSymlink>> versionUpgrades = new LinkedHashMap<>();

      // Starting point
      String version;
      String ecj = "lib/ecj-4.25.jar";
      String jakartaeeMigration = "lib/jakartaee-migration-1.0.5-shaded.jar";
      String mysql = "lib/mysql-connector-j-8.0.31.jar";
      String psql = "lib/postgresql-42.5.0.jar";

      // Versions
      version = "10.1.2-2" + oldSuffix;
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.5.1.jar");

      // switch to aorepo.org here, oldSuffix becomes suffix

      version = "10.1.4-1" + suffix;
      jakartaeeMigration = addUpgradeSymlink(optSlash, versionUpgrades, version, jakartaeeMigration, "lib/jakartaee-migration-1.0.6-shaded.jar");

      version = "10.1.5-1" + suffix;
      ecj = addUpgradeSymlink(optSlash, versionUpgrades, version, ecj, "lib/ecj-4.26.jar");
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-8.0.32.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.5.4.jar");

      version = "10.1.11-1" + suffix;
      ecj = addUpgradeSymlink(optSlash, versionUpgrades, version, ecj, "lib/ecj-4.27.jar");
      jakartaeeMigration = addUpgradeSymlink(optSlash, versionUpgrades, version, jakartaeeMigration, "lib/jakartaee-migration-1.0.7-shaded.jar");
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-8.0.33.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.6.0.jar");

      version = "10.1.11-2" + suffix;
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-8.1.0.jar");

      version = "10.1.12-1" + suffix;
      addUpgradeWithNoSymlinkChanges(versionUpgrades, version);

      version = "10.1.13-1" + suffix;
      addUpgradeWithNoSymlinkChanges(versionUpgrades, version);

      version = "10.1.14-1" + suffix;
      addUpgradeWithNoSymlinkChanges(versionUpgrades, version);

      version = "10.1.15-1" + suffix;
      addUpgradeWithNoSymlinkChanges(versionUpgrades, version);

      version = "10.1.16-1" + suffix;
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-8.2.0.jar");

      version = "10.1.24-1" + suffix;
      jakartaeeMigration = addUpgradeSymlink(optSlash, versionUpgrades, version, jakartaeeMigration, "lib/jakartaee-migration-1.0.8-shaded.jar");
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-8.4.0.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.7.3.jar");

      // tomcat-coyote-ffm.jar introduced

      version = "10.1.26-1" + suffix;
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-9.0.0.jar");
      @SuppressWarnings("unused")
      String tomcatCoyoteFfm = addNewSymlink(optSlash, versionUpgrades, version, "lib/tomcat-coyote-ffm.jar");

      version = "10.1.28-1" + suffix;
      addUpgradeWithNoSymlinkChanges(versionUpgrades, version);

      version = "10.1.34-1" + suffix;
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-9.2.0.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.7.5.jar");

      version = "10.1.42-1" + suffix;
      jakartaeeMigration = addUpgradeSymlink(optSlash, versionUpgrades, version, jakartaeeMigration, "lib/jakartaee-migration-1.0.9-shaded.jar");
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-9.3.0.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.7.7.jar");

      version = "10.1.43-1" + suffix;
      addUpgradeWithNoSymlinkChanges(versionUpgrades, version);

      version = "10.1.49-1" + suffix;
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-9.5.0.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.7.8.jar");

      version = "10.1.50-1" + suffix;
      jakartaeeMigration = addUpgradeSymlink(optSlash, versionUpgrades, version, jakartaeeMigration, "lib/jakartaee-migration-1.0.10-shaded.jar");

      version = "10.1.55-1" + suffix;
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-9.7.0.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.7.11.jar");

      version = "10.1.59-1" + suffix;
      jakartaeeMigration = addUpgradeSymlink(optSlash, versionUpgrades, version, jakartaeeMigration, "lib/jakartaee-migration-1.0.12-shaded.jar");
      mysql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, mysql, "lib/mysql-connector-j-26.7.0.jar");
      psql = addUpgradeSymlinkWithDevNull(optSlash, versionUpgrades, version, psql, "lib/postgresql-42.7.13.jar");

      // Downgrade support
      if (doDowngrades(tomcatDirectory, uid, gid, rpmVersion, versionUpgrades)) {
        needsRestart = true;
      }

      // Upgrade support
      if (doUpgrades(tomcatDirectory, uid, gid, rpmVersion, versionUpgrades)) {
        needsRestart = true;
      }
    }
    return needsRestart;
  }

  @Override
  OpenSslLifecycleType getOpenSslLifecycleType() throws IOException, SQLException {
    Version version = getRpmVersion(PackageManager.PackageName.APACHE_TOMCAT_10_1,
        PackageManager.PackageName.OLD_APACHE_TOMCAT_10_1);
    String suffix = OperatingSystemConfiguration.getOperatingSystemConfiguration().getPackageReleaseSuffix();
    return version.compareTo("10.1.24-1" + suffix) >= 0
        ? OpenSslLifecycleType.TOMCAT_10_1_24 : OpenSslLifecycleType.TOMCAT_8_5;
  }
}
