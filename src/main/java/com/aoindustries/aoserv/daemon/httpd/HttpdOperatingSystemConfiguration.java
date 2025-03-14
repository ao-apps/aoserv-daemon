/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.httpd;

import com.aoapps.lang.exception.WrappedException;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Operating system-specific configurations.
 *
 * @author  AO Industries, Inc.
 */
public enum HttpdOperatingSystemConfiguration {
  CENTOS_5_I686_AND_X86_64 {
    @Override
    public OperatingSystemConfiguration getOperatingSystemConfiguration() {
      return OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64;
    }

    //public String getDefaultPhpPostgresMinorVersion() {
    //    return "9.2";
    //}

    @Override
    public PosixPath getPhpCgiPath(String minorVersion) {
      try {
        if (minorVersion.startsWith("4.")) {
          return PosixPath.valueOf("/opt/php-4-i686/bin/php-cgi");
        } else if (minorVersion.startsWith("5.")) {
          return PosixPath.valueOf("/opt/php-" + minorVersion + "-i686/bin/php-cgi");
        } else {
          throw new AssertionError("Unexpected PHP version: " + minorVersion);
        }
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PosixPath getHttpdSitesDirectory() {
      return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
    }

    @Override
    public String getHttpdSitesOptSlash() {
      return "../../opt/";
    }

    @Override
    public PosixPath getHttpdSharedTomcatsDirectory() {
      return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64);
    }

    @Override
    public String getHttpdSharedTomcatsOptSlash() {
      return "../../opt/";
    }

    @Override
    public PackageManager.PackageName getAwstatsPackageName() {
      return PackageManager.PackageName.AWSTATS_6;
    }

    @Override
    public PosixPath getAwstatsVarDirectory() {
      try {
        return PosixPath.valueOf("/var/opt/awstats-6");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PosixPath getAwstatsBinDirectory() {
      try {
        return PosixPath.valueOf("/opt/awstats-6");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public String getListenDirective() {
      return "Listen";
    }

    @Override
    public PackageManager.PackageName getHttpdAfterNetworkOnlinePackageName() {
      return null;
    }

    @Override
    public PackageManager.PackageName getAlternateInstancePackageName() {
      return null;
    }

    @Override
    public PackageManager.PackageName getModJkPackageName() {
      return null;
    }

    @Override
    public boolean isApacheDefaultPrefork() {
      return true;
    }

    @Override
    public boolean isApacheProtocolsSupported() {
      return false;
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
    public PosixPath getPhpCgiPath(String minorVersion) {
      try {
        if (minorVersion.startsWith("5.")) {
          return PosixPath.valueOf("/opt/php-" + minorVersion + "/bin/php-cgi");
        } else if (minorVersion.startsWith("7.")) {
          return PosixPath.valueOf("/opt/php-" + minorVersion + "/bin/php-cgi");
        } else if (minorVersion.startsWith("8.")) {
          return PosixPath.valueOf("/opt/php-" + minorVersion + "/bin/php-cgi");
        } else {
          throw new AssertionError("Unexpected PHP version: " + minorVersion);
        }
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PosixPath getHttpdSitesDirectory() {
      return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.CENTOS_7_X86_64);
    }

    @Override
    public String getHttpdSitesOptSlash() {
      return "../../../opt/";
    }

    @Override
    public PosixPath getHttpdSharedTomcatsDirectory() {
      return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.CENTOS_7_X86_64);
    }

    @Override
    public String getHttpdSharedTomcatsOptSlash() {
      return "../../../../opt/";
    }

    @Override
    public PackageManager.PackageName getAwstatsPackageName() {
      return PackageManager.PackageName.AWSTATS;
    }

    @Override
    public PosixPath getAwstatsVarDirectory() {
      try {
        return PosixPath.valueOf("/var/opt/awstats");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PosixPath getAwstatsBinDirectory() {
      try {
        return PosixPath.valueOf("/opt/awstats");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public String getListenDirective() {
      return "Listen";
    }

    @Override
    public PackageManager.PackageName getHttpdAfterNetworkOnlinePackageName() {
      return PackageManager.PackageName.HTTPD_AFTER_NETWORK_ONLINE;
    }

    @Override
    public PackageManager.PackageName getAlternateInstancePackageName() {
      return PackageManager.PackageName.HTTPD_N;
    }

    @Override
    public PackageManager.PackageName getModJkPackageName() {
      return PackageManager.PackageName.TOMCAT_CONNECTORS;
    }

    @Override
    public boolean isApacheDefaultPrefork() {
      return true;
    }

    @Override
    public boolean isApacheProtocolsSupported() {
      return false;
    }
  },
  ROCKY_9_X86_64 {
    @Override
    public OperatingSystemConfiguration getOperatingSystemConfiguration() {
      return OperatingSystemConfiguration.ROCKY_9_X86_64;
    }

    //public String getDefaultPhpPostgresMinorVersion() {
    //    return "15";
    //}

    @Override
    public PosixPath getPhpCgiPath(String minorVersion) {
      try {
        if (minorVersion.startsWith("8.")) {
          return PosixPath.valueOf("/opt/php-" + minorVersion + "/bin/php-cgi");
        } else {
          throw new AssertionError("Unexpected PHP version: " + minorVersion);
        }
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PosixPath getHttpdSitesDirectory() {
      return OperatingSystemVersion.getHttpdSitesDirectory(OperatingSystemVersion.ROCKY_9_X86_64);
    }

    @Override
    public String getHttpdSitesOptSlash() {
      return "../../../opt/";
    }

    @Override
    public PosixPath getHttpdSharedTomcatsDirectory() {
      return OperatingSystemVersion.getHttpdSharedTomcatsDirectory(OperatingSystemVersion.ROCKY_9_X86_64);
    }

    @Override
    public String getHttpdSharedTomcatsOptSlash() {
      return "../../../../opt/";
    }

    @Override
    public PackageManager.PackageName getAwstatsPackageName() {
      return PackageManager.PackageName.AWSTATS;
    }

    @Override
    public PosixPath getAwstatsVarDirectory() {
      try {
        return PosixPath.valueOf("/var/opt/awstats");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PosixPath getAwstatsBinDirectory() {
      try {
        return PosixPath.valueOf("/opt/awstats");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public String getListenDirective() {
      return "ListenFree";
    }

    /**
     * Rocky 9 uses ListenFree instead of Listen, it's okay for Apache instances to start before networking is fully up.
     */
    @Override
    public PackageManager.PackageName getHttpdAfterNetworkOnlinePackageName() {
      return null;
    }

    /**
     * The httpd package in Rocky 9 includes its own <code>httpd@.service</code>.
     */
    @Override
    public PackageManager.PackageName getAlternateInstancePackageName() {
      return null;
    }

    @Override
    public PackageManager.PackageName getModJkPackageName() {
      return PackageManager.PackageName.MOD_JK;
    }

    @Override
    public boolean isApacheDefaultPrefork() {
      return false;
    }

    @Override
    public boolean isApacheProtocolsSupported() {
      return true;
    }
  };

  /**
   * Gets the operating system configuration for this server, as
   * determined by the <code>AoservDaemon.getThisServer()</code>
   * method.
   *
   * @see  AoservDaemon#getThisServer()
   */
  public static HttpdOperatingSystemConfiguration getHttpOperatingSystemConfiguration() throws IOException, SQLException {
    OperatingSystemVersion osv = AoservDaemon.getThisServer().getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    switch (osvId) {
      case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64:
        return CENTOS_5_I686_AND_X86_64;
      case OperatingSystemVersion.CENTOS_7_X86_64:
        return CENTOS_7_X86_64;
      case OperatingSystemVersion.ROCKY_9_X86_64:
        return ROCKY_9_X86_64;
      default:
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
  }

  /**
   * Gets the configuration for general tasks.
   */
  public abstract OperatingSystemConfiguration getOperatingSystemConfiguration();

  ///**
  // * The version of PostgreSQL minor version used by the default PHP minor version.
  // */
  //public abstract String getDefaultPhpPostgresMinorVersion();

  /**
   * Gets the full path to the PHP CGI script for the provided PHP minor version.
   */
  public abstract PosixPath getPhpCgiPath(String minorVersion);

  /**
   * Gets the directory that contains the website directories.
   */
  public abstract PosixPath getHttpdSitesDirectory();

  /**
   * Gets the relative path from CATALINA_HOME to /opt/ for the website directories, including the trailing slash.
   */
  public abstract String getHttpdSitesOptSlash();

  /**
   * Gets the directory that contains the shared tomcat directories.
   */
  public abstract PosixPath getHttpdSharedTomcatsDirectory();

  /**
   * Gets the relative path from CATALINA_HOME to /opt/ for the shared tomcat directories, including the trailing slash.
   */
  public abstract String getHttpdSharedTomcatsOptSlash();

  /**
   * Gets the configuration directory used by AWStats.
   */
  public PosixPath getAwstatsConfigDirectory() {
    try {
      return PosixPath.valueOf("/etc/awstats");
    } catch (ValidationException e) {
      throw new WrappedException(e);
    }
  }

  /**
   * Gets the package that should be installed for AWStats or <code>null</code>
   * for not automatic package management.
   */
  public abstract PackageManager.PackageName getAwstatsPackageName();

  /**
   * Gets the main AWStats directory.
   */
  public abstract PosixPath getAwstatsVarDirectory();

  /**
   * Gets the AWStats hosts directory.
   */
  public PosixPath getAwstatsHostsDirectory() {
    try {
      return PosixPath.valueOf(getAwstatsVarDirectory() + "/hosts");
    } catch (ValidationException e) {
      throw new WrappedException(e);
    }
  }

  /**
   * Gets the AWStats bin directory.
   */
  public abstract PosixPath getAwstatsBinDirectory();

  /**
   * Gets the AWStats icon directory.
   */
  public PosixPath getAwstatsIconDirectory() {
    try {
      return PosixPath.valueOf(getAwstatsBinDirectory() + "/wwwroot/icon");
    } catch (ValidationException e) {
      throw new WrappedException(e);
    }
  }

  /**
   * Gets the type of Listen directive to use for Apache.
   */
  public abstract String getListenDirective();

  /**
   * Gets the package to install when there is a non-loopback and non-wildcard bind or {@code null} for none required.
   */
  public abstract PackageManager.PackageName getHttpdAfterNetworkOnlinePackageName();

  /**
   * Gets the package to install when there is an alternate Aapche instance or {@code null} for none required.
   */
  public abstract PackageManager.PackageName getAlternateInstancePackageName();

  /**
   * Gets the package to install for mod_jk or {@code null} for none required.
   */
  public abstract PackageManager.PackageName getModJkPackageName();

  /**
   * Default to Apache prefork MPM (for older versions).
   * When not the default, prefork is still selected when mod_php is enabled.
   */
  public abstract boolean isApacheDefaultPrefork();

  /**
   * Is the <a href="https://httpd.apache.org/docs/2.4/mod/core.html#protocols">Apache Protocols directive</a> supported.
   */
  public abstract boolean isApacheProtocolsSupported();
}
