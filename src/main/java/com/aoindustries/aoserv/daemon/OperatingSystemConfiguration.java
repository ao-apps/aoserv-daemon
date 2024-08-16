/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon;

import com.aoapps.lang.exception.WrappedException;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Operating system-specific configurations.
 *
 * @author  AO Industries, Inc.
 */
public enum OperatingSystemConfiguration {
  CENTOS_5_I686_AND_X86_64 {
    @Override
    public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
      return HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64;
    }

    /**
     * This replace command is copied from /opt/mysql-5.7[-i686]/bin/replace
     * TODO: Just use sed instead.
     */
    @Override
    public PosixPath getReplaceCommand() {
      try {
        return PosixPath.valueOf("/opt/aoserv-daemon/bin/replace");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    //@Override
    //public PosixPath getAOServClientScriptInclude() {
    //  return "/opt/aoserv-client/scripts/aoserv-client.sh";
    //}

    @Override
    public PosixPath getPostgresPath(String minorVersion) {
      try {
        return PosixPath.valueOf("/opt/postgresql-" + minorVersion + "-i686");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    //public PosixPath getMySQLConnectorJavaJarPath() {
    //  return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
    //}

    @Override
    public PosixPath getDefaultJdkProfileSh() {
      try {
        return PosixPath.valueOf("/opt/jdk1-i686/setenv.sh");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PackageManager.PackageName getDefaultJdkPackageName() {
      return PackageManager.PackageName.JDK1_I686;
    }

    @Override
    public PosixPath getJdk17ProfileSh() {
      try {
        return PosixPath.valueOf("/opt/jdk1.7-i686/setenv.sh");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PackageManager.PackageName getJdk17PackageName() {
      return PackageManager.PackageName.JDK17_I686;
    }

    @Override
    public String getPackageReleaseSuffix() {
      return ".aorepo";
    }

    @Override
    public String getOldPackageReleaseSuffix() {
      return ".ao";
    }

    @Override
    public PackageManager.PackageName getPerlPackageName() {
      return PackageManager.PackageName.PERL;
    }
  },
  CENTOS_5DOM0_I686 {
    @Override
    public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
      return null;
    }

    @Override
    public PosixPath getReplaceCommand() {
      return CENTOS_5_I686_AND_X86_64.getReplaceCommand();
    }

    //public PosixPath getAOServClientScriptInclude() {
    //  return "/opt/aoserv-client/scripts/aoserv-client.sh";
    //}

    @Override
    public PosixPath getPostgresPath(String minorVersion) {
      return null;
    }

    //public PosixPath getMySQLConnectorJavaJarPath() {
    //  return null;
    //}

    @Override
    public PosixPath getDefaultJdkProfileSh() {
      return CENTOS_5_I686_AND_X86_64.getDefaultJdkProfileSh();
    }

    @Override
    public PackageManager.PackageName getDefaultJdkPackageName() {
      return CENTOS_5_I686_AND_X86_64.getDefaultJdkPackageName();
    }

    @Override
    public PosixPath getJdk17ProfileSh() {
      return CENTOS_5_I686_AND_X86_64.getJdk17ProfileSh();
    }

    @Override
    public PackageManager.PackageName getJdk17PackageName() {
      return CENTOS_5_I686_AND_X86_64.getJdk17PackageName();
    }

    @Override
    public String getPackageReleaseSuffix() {
      return CENTOS_5_I686_AND_X86_64.getPackageReleaseSuffix();
    }

    @Override
    public String getOldPackageReleaseSuffix() {
      return CENTOS_5_I686_AND_X86_64.getOldPackageReleaseSuffix();
    }

    @Override
    public PackageManager.PackageName getPerlPackageName() {
      return CENTOS_5_I686_AND_X86_64.getPerlPackageName();
    }
  },
  CENTOS_5DOM0_X86_64 {
    @Override
    public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
      return null;
    }

    @Override
    public PosixPath getReplaceCommand() {
      return CENTOS_5_I686_AND_X86_64.getReplaceCommand();
    }

    //public PosixPath getAOServClientScriptInclude() {
    //  return "/opt/aoserv-client/scripts/aoserv-client.sh";
    //}

    @Override
    public PosixPath getPostgresPath(String minorVersion) {
      return null;
    }

    //public PosixPath getMySQLConnectorJavaJarPath() {
    //  return null;
    //}

    @Override
    public PosixPath getDefaultJdkProfileSh() {
      return CENTOS_5_I686_AND_X86_64.getDefaultJdkProfileSh();
    }

    @Override
    public PackageManager.PackageName getDefaultJdkPackageName() {
      return CENTOS_5_I686_AND_X86_64.getDefaultJdkPackageName();
    }

    @Override
    public PosixPath getJdk17ProfileSh() {
      return CENTOS_5_I686_AND_X86_64.getJdk17ProfileSh();
    }

    @Override
    public PackageManager.PackageName getJdk17PackageName() {
      return CENTOS_5_I686_AND_X86_64.getJdk17PackageName();
    }

    @Override
    public String getPackageReleaseSuffix() {
      return CENTOS_5_I686_AND_X86_64.getPackageReleaseSuffix();
    }

    @Override
    public String getOldPackageReleaseSuffix() {
      return CENTOS_5_I686_AND_X86_64.getOldPackageReleaseSuffix();
    }

    @Override
    public PackageManager.PackageName getPerlPackageName() {
      return CENTOS_5_I686_AND_X86_64.getPerlPackageName();
    }
  },
  CENTOS_7_X86_64 {
    @Override
    public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
      return HttpdOperatingSystemConfiguration.CENTOS_7_X86_64;
    }

    /**
     * This replace command is copied from /opt/mysql-5.7/bin/replace
     * TODO: Just use sed instead.
     */
    @Override
    public PosixPath getReplaceCommand() {
      try {
        return PosixPath.valueOf("/opt/aoserv-daemon/bin/replace");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    //@Override
    //public PosixPath getAOServClientScriptInclude() {
    //  return "/opt/aoserv-client/scripts/aoserv-client.sh";
    //}

    @Override
    public PosixPath getPostgresPath(String minorVersion) {
      try {
        return PosixPath.valueOf("/opt/postgresql-" + minorVersion);
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    //public PosixPath getMySQLConnectorJavaJarPath() {
    //  return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
    //}

    @Override
    public PosixPath getDefaultJdkProfileSh() {
      try {
        return PosixPath.valueOf("/opt/jdk-lts/profile.sh");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PackageManager.PackageName getDefaultJdkPackageName() {
      return PackageManager.PackageName.JDK_LTS;
    }

    @Override
    public PosixPath getJdk17ProfileSh() {
      try {
        return PosixPath.valueOf("/opt/jdk1.7/setenv.sh");
      } catch (ValidationException e) {
        throw new WrappedException(e);
      }
    }

    @Override
    public PackageManager.PackageName getJdk17PackageName() {
      return PackageManager.PackageName.JDK17;
    }

    @Override
    public String getPackageReleaseSuffix() {
      return ".el7.aorepo";
    }

    @Override
    public String getOldPackageReleaseSuffix() {
      return ".el7.ao";
    }

    @Override
    public PackageManager.PackageName getPerlPackageName() {
      return PackageManager.PackageName.PERL;
    }
  },
  CENTOS_7DOM0_X86_64 {
    @Override
    public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
      return null;
    }

    @Override
    public PosixPath getReplaceCommand() {
      return CENTOS_7_X86_64.getReplaceCommand();
    }

    //@Override
    //public PosixPath getAOServClientScriptInclude() {
    //  return "/opt/aoserv-client/scripts/aoserv-client.sh";
    //}

    @Override
    public PosixPath getPostgresPath(String minorVersion) {
      return null;
    }

    //public PosixPath getMySQLConnectorJavaJarPath() {
    //  return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
    //}

    @Override
    public PosixPath getDefaultJdkProfileSh() {
      return CENTOS_7_X86_64.getDefaultJdkProfileSh();
    }

    @Override
    public PackageManager.PackageName getDefaultJdkPackageName() {
      return CENTOS_7_X86_64.getDefaultJdkPackageName();
    }

    @Override
    public PosixPath getJdk17ProfileSh() {
      return CENTOS_7_X86_64.getJdk17ProfileSh();
    }

    @Override
    public PackageManager.PackageName getJdk17PackageName() {
      return CENTOS_7_X86_64.getJdk17PackageName();
    }

    @Override
    public String getPackageReleaseSuffix() {
      return CENTOS_7_X86_64.getPackageReleaseSuffix();
    }

    @Override
    public String getOldPackageReleaseSuffix() {
      return CENTOS_7_X86_64.getOldPackageReleaseSuffix();
    }

    @Override
    public PackageManager.PackageName getPerlPackageName() {
      return CENTOS_7_X86_64.getPerlPackageName();
    }
  };

  /**
   * Gets the operating system configuration for this server, as
   * determined by the <code>AoservDaemon.getThisServer()</code>
   * method.
   *
   * @see  AoservDaemon#getThisServer()
   */
  public static OperatingSystemConfiguration getOperatingSystemConfiguration() throws IOException, SQLException {
    OperatingSystemVersion osv = AoservDaemon.getThisServer().getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    switch (osvId) {
      case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64:
        return CENTOS_5_I686_AND_X86_64;
      case OperatingSystemVersion.CENTOS_5_DOM0_I686:
        return CENTOS_5DOM0_I686;
      case OperatingSystemVersion.CENTOS_5_DOM0_X86_64:
        return CENTOS_5DOM0_X86_64;
      case OperatingSystemVersion.CENTOS_7_X86_64:
        return CENTOS_7_X86_64;
      case OperatingSystemVersion.CENTOS_7_DOM0_X86_64:
        return CENTOS_7DOM0_X86_64;
      default:
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
  }

  /**
   * Gets the configuration for HTTP-related tasks or <code>null</code>
   * if this OS version doesn't have any HTTP servers.
   */
  public abstract HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration();

  /**
   * Gets the full path to the replace command or <code>null</code>
   * if unavailable.
   */
  public abstract PosixPath getReplaceCommand();

  ///**
  // * Gets the full path that a shell script would use to include the AOServ
  // * Client.
  // */
  //public abstract PosixPath getAOServClientScriptInclude();

  /**
   * Gets the path to the PostgreSQL installation directory for the provided
   * minor version (7.3, 8.1, 8.3, ...).
   *
   * @return  The full path or <code>null</code> if PostgreSQL not supported.
   */
  public abstract PosixPath getPostgresPath(String minorVersion);

  ///**
  // * Gets the full path to the preferred MySQL Java JDBC driver JAR or
  // * <code>null</code> if none available.
  // */
  //public abstract PosixPath getMySQLConnectorJavaJarPath();

  /**
   * Gets the path to the default JDK profile.sh script.
   */
  public abstract PosixPath getDefaultJdkProfileSh();

  /**
   * Gets the package that provides the default JDK.
   */
  public abstract PackageManager.PackageName getDefaultJdkPackageName();

  /**
   * Gets the path to the JDK 1.7 profile.sh script.
   */
  public abstract PosixPath getJdk17ProfileSh();

  /**
   * Gets the package that provides JDK 1.7.
   */
  public abstract PackageManager.PackageName getJdk17PackageName();

  /**
   * Gets the expected suffix on RPM release numbers after the move to AORepo.org.
   */
  public abstract String getPackageReleaseSuffix();

  /**
   * Gets the expected suffix on RPM release numbers before the move to AORepo.org.
   */
  public abstract String getOldPackageReleaseSuffix();

  /**
   * Gets the package that provides PERL.
   */
  public abstract PackageManager.PackageName getPerlPackageName();
}
