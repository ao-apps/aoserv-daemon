/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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
import java.util.List;
import java.util.Set;

/**
 * Some common code for Tomcat 8.5.X
 *
 * @author  AO Industries, Inc.
 */
final class TomcatCommon_8_5_X extends VersionedTomcatCommon {

  private static final TomcatCommon_8_5_X instance = new TomcatCommon_8_5_X();

  static TomcatCommon_8_5_X getInstance() {
    return instance;
  }

  private TomcatCommon_8_5_X() {
    // Do nothing
  }

  @Override
  protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
    return EnumSet.of(
        OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
        PackageManager.PackageName.AOSERV_PROFILE_D,
        PackageManager.PackageName.APACHE_TOMCAT_8_5
    );
  }

  @Override
  protected String getApacheTomcatDir() {
    return "apache-tomcat-8.5";
  }

  // Note: Updates here should be matched between all VersionTomcat versions (8.5, 9.0, 10.0 currently)
  //       in order to allow both upgrade and downgrade between them.
  @Override
  protected List<Install> getInstallFiles(String optSlash, PosixFile installDir, int confMode) throws IOException, SQLException {
    return Arrays.asList(
        new Mkdir        ("bin", 0770),
        new Symlink      ("bin/bootstrap.jar"),
        new ProfileScript("bin/catalina.sh"),
        new ProfileScript("bin/ciphers.sh"), // Tomcat 8.5+
        new Symlink      ("bin/commons-daemon.jar"),
        // Skipped bin/commons-daemon-native.tar.gz
        new Delete       ("bin/commons-logging-api.jar"), // Tomcat 5.5
        new ProfileScript("bin/configtest.sh"),
        // Skipped bin/daemon.sh from Tomcat 9.0
        new ProfileScript("bin/digest.sh"),
        new Delete       ("bin/jasper.sh"), // Tomcat 4.1
        new Delete       ("bin/jspc.sh"), // Tomcat 4.1
        // Skipped bin/makebase.sh from Tomcat 9.0
        new Delete       ("bin/migrate.sh"), // Tomcat 10.0+
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
        // Skipped bin/tomcat-native.tar.gz
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
   * Upgrades the Tomcat 8.5.X installed in the provided directory.
   *
   * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
   */
  @Override
  protected boolean upgradeTomcatDirectory(String optSlash, PosixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
    // TODO: This might be able to simply use the same lnAll as used to initially create the lib/ directory
    boolean needsRestart = false;
    OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    if (osConfig == OperatingSystemConfiguration.CENTOS_7_X86_64) {
      PackageManager.Rpm rpm = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_8_5);
      Version version = new Version(rpm.getVersion(), rpm.getRelease());
      String suffix = osConfig.getPackageReleaseSuffix();
      // Downgrade support
      if (version.compareTo("8.5.81-2" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // mysql-connector-java-8.0.30.jar -> mysql-connector-java-8.0.29.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.30.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.29.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.30.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.30.jar",
                "lib/mysql-connector-java-8.0.29.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.29.jar"
            ),
            // postgresql-42.4.1.jar -> postgresql-42.4.0.jar
            new UpgradeSymlink(
                "lib/postgresql-42.4.1.jar",
                "/dev/null",
                "lib/postgresql-42.4.0.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.4.1.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.4.1.jar",
                "lib/postgresql-42.4.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.4.0.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.81-1" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // postgresql-42.4.0.jar -> postgresql-42.3.6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.4.0.jar",
                "/dev/null",
                "lib/postgresql-42.3.6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.4.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.4.0.jar",
                "lib/postgresql-42.3.6.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.6.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.79-1" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // postgresql-42.3.6.jar -> postgresql-42.3.4.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.6.jar",
                "/dev/null",
                "lib/postgresql-42.3.4.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.6.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.6.jar",
                "lib/postgresql-42.3.4.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.4.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.78-3" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // mysql-connector-java-8.0.29.jar -> mysql-connector-java-8.0.28.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.29.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.28.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.29.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.29.jar",
                "lib/mysql-connector-java-8.0.28.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.28.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.78-2" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // postgresql-42.3.4.jar -> postgresql-42.3.3.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.4.jar",
                "/dev/null",
                "lib/postgresql-42.3.3.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.4.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.4.jar",
                "lib/postgresql-42.3.3.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.3.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.78-1" + suffix) < 0) {
        // 8.5.78-1 has same files as 8.5.77-1
      }
      if (version.compareTo("8.5.77-1" + suffix) < 0) {
        // 8.5.77-1 has same files as 8.5.76-1
      }
      if (version.compareTo("8.5.76-1" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // postgresql-42.3.3.jar -> postgresql-42.3.1.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.3.jar",
                "/dev/null",
                "lib/postgresql-42.3.1.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.3.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.3.jar",
                "lib/postgresql-42.3.1.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.1.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.75-1" + suffix) < 0) {
        // 8.5.75-1 has same files as 8.5.73-2
      }
      if (version.compareTo("8.5.73-2" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // mysql-connector-java-8.0.28.jar -> mysql-connector-java-8.0.27.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.28.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.27.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.28.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.28.jar",
                "lib/mysql-connector-java-8.0.27.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.27.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.73-1" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // postgresql-42.3.1.jar -> postgresql-42.3.0.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.1.jar",
                "/dev/null",
                "lib/postgresql-42.3.0.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.1.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.1.jar",
                "lib/postgresql-42.3.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.0.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.72-1" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // mysql-connector-java-8.0.27.jar -> mysql-connector-java-8.0.26.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.27.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.26.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.27.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.27.jar",
                "lib/mysql-connector-java-8.0.26.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.26.jar"
            ),
            // postgresql-42.3.0.jar -> postgresql-42.2.24.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.0.jar",
                "/dev/null",
                "lib/postgresql-42.2.24.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.0.jar",
                "lib/postgresql-42.2.24.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.24.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.71-2" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // postgresql-42.2.24.jar -> postgresql-42.2.23.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.24.jar",
                "/dev/null",
                "lib/postgresql-42.2.23.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.24.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.24.jar",
                "lib/postgresql-42.2.23.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.23.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.71-1" + suffix) < 0) {
        // 8.5.71-1 has same files as 8.5.70-1
      }
      if (version.compareTo("8.5.70-1" + suffix) < 0) {
        // 8.5.70-1 has same files as 8.5.69-1
      }
      if (version.compareTo("8.5.69-1" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // mysql-connector-java-8.0.26.jar -> mysql-connector-java-8.0.25.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.26.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.25.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.26.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.26.jar",
                "lib/mysql-connector-java-8.0.25.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.25.jar"
            ),
            // postgresql-42.2.23.jar -> postgresql-42.2.22.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.23.jar",
                "/dev/null",
                "lib/postgresql-42.2.22.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.23.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.23.jar",
                "lib/postgresql-42.2.22.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.22.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.68-1" + suffix) < 0) {
        UpgradeSymlink[] downgradeSymlinks = {
            // postgresql-42.2.22.jar -> postgresql-42.2.20.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.22.jar",
                "/dev/null",
                "lib/postgresql-42.2.20.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.22.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.22.jar",
                "lib/postgresql-42.2.20.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.20.jar"
            ),
        };
        for (UpgradeSymlink symlink : downgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.66-1" + suffix) < 0) {
        throw new IllegalStateException("Version of Tomcat older than expected: " + version);
      }
      // Upgrade support
      if (version.compareTo("8.5.66-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // mysql-connector-java-8.0.24.jar -> mysql-connector-java-8.0.25.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.24.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.25.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.24.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.24.jar",
                "lib/mysql-connector-java-8.0.25.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.25.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.68-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // postgresql-42.2.20.jar -> postgresql-42.2.22.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.20.jar",
                "/dev/null",
                "lib/postgresql-42.2.22.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.20.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.20.jar",
                "lib/postgresql-42.2.22.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.22.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.69-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // mysql-connector-java-8.0.25.jar -> mysql-connector-java-8.0.26.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.25.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.26.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.25.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.25.jar",
                "lib/mysql-connector-java-8.0.26.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.26.jar"
            ),
            // postgresql-42.2.22.jar -> postgresql-42.2.23.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.22.jar",
                "/dev/null",
                "lib/postgresql-42.2.23.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.22.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.22.jar",
                "lib/postgresql-42.2.23.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.23.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.70-1" + suffix) >= 0) {
        // 8.5.70-1 has same files as 8.5.69-1
      }
      if (version.compareTo("8.5.71-1" + suffix) >= 0) {
        // 8.5.71-1 has same files as 8.5.70-1
      }
      if (version.compareTo("8.5.71-2" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // postgresql-42.2.23.jar -> postgresql-42.2.24.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.23.jar",
                "/dev/null",
                "lib/postgresql-42.2.24.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.23.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.23.jar",
                "lib/postgresql-42.2.24.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.24.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.72-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // mysql-connector-java-8.0.26.jar -> mysql-connector-java-8.0.27.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.26.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.27.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.26.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.26.jar",
                "lib/mysql-connector-java-8.0.27.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.27.jar"
            ),
            // postgresql-42.2.24.jar -> postgresql-42.3.0.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.24.jar",
                "/dev/null",
                "lib/postgresql-42.3.0.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.24.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.2.24.jar",
                "lib/postgresql-42.3.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.0.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.73-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // postgresql-42.3.0.jar -> postgresql-42.3.1.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.0.jar",
                "/dev/null",
                "lib/postgresql-42.3.1.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.0.jar",
                "lib/postgresql-42.3.1.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.1.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.73-2" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // mysql-connector-java-8.0.27.jar -> mysql-connector-java-8.0.28.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.27.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.28.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.27.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.27.jar",
                "lib/mysql-connector-java-8.0.28.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.28.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.75-1" + suffix) >= 0) {
        // 8.5.75-1 has same files as 8.5.73-2
      }
      if (version.compareTo("8.5.76-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // postgresql-42.3.1.jar -> postgresql-42.3.3.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.1.jar",
                "/dev/null",
                "lib/postgresql-42.3.3.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.1.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.1.jar",
                "lib/postgresql-42.3.3.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.3.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.77-1" + suffix) >= 0) {
        // 8.5.77-1 has same files as 8.5.76-1
      }
      if (version.compareTo("8.5.78-1" + suffix) >= 0) {
        // 8.5.78-1 has same files as 8.5.77-1
      }
      if (version.compareTo("8.5.78-2" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // postgresql-42.3.3.jar -> postgresql-42.3.4.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.3.jar",
                "/dev/null",
                "lib/postgresql-42.3.4.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.3.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.3.jar",
                "lib/postgresql-42.3.4.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.4.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.78-3" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // mysql-connector-java-8.0.28.jar -> mysql-connector-java-8.0.29.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.28.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.29.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.28.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.28.jar",
                "lib/mysql-connector-java-8.0.29.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.29.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.79-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // postgresql-42.3.4.jar -> postgresql-42.3.6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.4.jar",
                "/dev/null",
                "lib/postgresql-42.3.6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.4.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.4.jar",
                "lib/postgresql-42.3.6.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.6.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.81-1" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // postgresql-42.3.6.jar -> postgresql-42.4.0.jar
            new UpgradeSymlink(
                "lib/postgresql-42.3.6.jar",
                "/dev/null",
                "lib/postgresql-42.4.0.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.3.6.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.3.6.jar",
                "lib/postgresql-42.4.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.4.0.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.81-2" + suffix) >= 0) {
        UpgradeSymlink[] upgradeSymlinks = {
            // mysql-connector-java-8.0.29.jar -> mysql-connector-java-8.0.30.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.29.jar",
                "/dev/null",
                "lib/mysql-connector-java-8.0.30.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-8.0.29.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.29.jar",
                "lib/mysql-connector-java-8.0.30.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/mysql-connector-java-8.0.30.jar"
            ),
            // postgresql-42.4.0.jar -> postgresql-42.4.1.jar
            new UpgradeSymlink(
                "lib/postgresql-42.4.0.jar",
                "/dev/null",
                "lib/postgresql-42.4.1.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.4.0.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.4.0.jar",
                "lib/postgresql-42.4.1.jar",
                "../" + optSlash + "apache-tomcat-8.5/lib/postgresql-42.4.1.jar"
            ),
        };
        for (UpgradeSymlink symlink : upgradeSymlinks) {
          if (symlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      }
      if (version.compareTo("8.5.81-2" + suffix) > 0) {
        throw new IllegalStateException("Version of Tomcat newer than expected: " + version);
      }
    }
    return needsRestart;
  }
}
