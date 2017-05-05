/*
 * Copyright 2008-2013, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.util.WrappedException;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Operating system-specific configurations.
 *
 * @author  AO Industries, Inc.
 */
public enum OperatingSystemConfiguration {
	REDHAT_ES_4_X86_64 {
		@Override
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return HttpdOperatingSystemConfiguration.REDHAT_ES_4_X86_64;
		}
		@Override
		public UnixPath getOpensslDefaultCaFile() {
			try {
				return UnixPath.valueOf("/etc/ssl/CA/ca.txt");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public UnixPath getReplaceCommand() {
			try {
				return UnixPath.valueOf("/opt/mysql-5.0/bin/replace");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		//@Override
		//public UnixPath getAOServClientScriptInclude() {
		//	return "/opt/aoserv-client/scripts/aoserv-client.sh";
		//}
		@Override
		public UnixPath getPostgresPath(String minorVersion) {
			try {
				return UnixPath.valueOf("/opt/postgresql-"+minorVersion);
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		//public UnixPath getMySQLConnectorJavaJarPath() {
		//	return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
		//}
		@Override
		public UnixPath getDefaultJdkSetEnv() {
			try {
				return UnixPath.valueOf("/opt/jdk1/setenv.sh");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public PackageManager.PackageName getDefaultJdkPackageName() {
			return PackageManager.PackageName.JDK1;
		}
		@Override
		public UnixPath getJdk17SetEnv() {
			try {
				return UnixPath.valueOf("/opt/jdk1.7/setenv.sh");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public PackageManager.PackageName getJdk17PackageName() {
			return PackageManager.PackageName.JDK17;
		}
	},
	CENTOS_5_I686_AND_X86_64 {
		@Override
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64;
		}
		@Override
		public UnixPath getOpensslDefaultCaFile() {
			try {
				return UnixPath.valueOf("/etc/pki/tls/certs/ca-bundle.crt");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		/**
		 * This replace command is copied from /opt/mysql-5.7[-i686]/bin/replace
		 * TODO: Just use sed instead.
		 */
		@Override
		public UnixPath getReplaceCommand() {
			try {
				return UnixPath.valueOf("/opt/aoserv-daemon/bin/replace");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		//@Override
		//public UnixPath getAOServClientScriptInclude() {
		//	return "/opt/aoserv-client/scripts/aoserv-client.sh";
		//}
		@Override
		public UnixPath getPostgresPath(String minorVersion) {
			try {
				return UnixPath.valueOf("/opt/postgresql-"+minorVersion+"-i686");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		//public UnixPath getMySQLConnectorJavaJarPath() {
		//	return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
		//}
		@Override
		public UnixPath getDefaultJdkSetEnv() {
			try {
				return UnixPath.valueOf("/opt/jdk1-i686/setenv.sh");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public PackageManager.PackageName getDefaultJdkPackageName() {
			return PackageManager.PackageName.JDK1_I686;
		}
		@Override
		public UnixPath getJdk17SetEnv() {
			try {
				return UnixPath.valueOf("/opt/jdk1.7-i686/setenv.sh");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public PackageManager.PackageName getJdk17PackageName() {
			return PackageManager.PackageName.JDK17_I686;
		}
	},
	CENTOS_5DOM0_I686 {
		@Override
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return null;
		}
		@Override
		public UnixPath getOpensslDefaultCaFile() {
			return CENTOS_5_I686_AND_X86_64.getOpensslDefaultCaFile();
		}
		@Override
		public UnixPath getReplaceCommand() {
			return CENTOS_5_I686_AND_X86_64.getReplaceCommand();
		}
		//public UnixPath getAOServClientScriptInclude() {
		//	return "/opt/aoserv-client/scripts/aoserv-client.sh";
		//}
		@Override
		public UnixPath getPostgresPath(String minorVersion) {
			return null;
		}
		//public UnixPath getMySQLConnectorJavaJarPath() {
		//	return null;
		//}
		@Override
		public UnixPath getDefaultJdkSetEnv() {
			return CENTOS_5_I686_AND_X86_64.getDefaultJdkSetEnv();
		}
		@Override
		public PackageManager.PackageName getDefaultJdkPackageName() {
			return CENTOS_5_I686_AND_X86_64.getDefaultJdkPackageName();
		}
		@Override
		public UnixPath getJdk17SetEnv() {
			return CENTOS_5_I686_AND_X86_64.getJdk17SetEnv();
		}
		@Override
		public PackageManager.PackageName getJdk17PackageName() {
			return CENTOS_5_I686_AND_X86_64.getJdk17PackageName();
		}
	},
	CENTOS_5DOM0_X86_64 {
		@Override
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return null;
		}
		@Override
		public UnixPath getOpensslDefaultCaFile() {
			return CENTOS_5_I686_AND_X86_64.getOpensslDefaultCaFile();
		}
		@Override
		public UnixPath getReplaceCommand() {
			return CENTOS_5_I686_AND_X86_64.getReplaceCommand();
		}
		//public UnixPath getAOServClientScriptInclude() {
		//	return "/opt/aoserv-client/scripts/aoserv-client.sh";
		//}
		@Override
		public UnixPath getPostgresPath(String minorVersion) {
			return null;
		}
		//public UnixPath getMySQLConnectorJavaJarPath() {
		//	return null;
		//}
		@Override
		public UnixPath getDefaultJdkSetEnv() {
			return CENTOS_5_I686_AND_X86_64.getDefaultJdkSetEnv();
		}
		@Override
		public PackageManager.PackageName getDefaultJdkPackageName() {
			return CENTOS_5_I686_AND_X86_64.getDefaultJdkPackageName();
		}
		@Override
		public UnixPath getJdk17SetEnv() {
			return CENTOS_5_I686_AND_X86_64.getJdk17SetEnv();
		}
		@Override
		public PackageManager.PackageName getJdk17PackageName() {
			return CENTOS_5_I686_AND_X86_64.getJdk17PackageName();
		}
	},
	CENTOS_7_X86_64 {
		@Override
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return HttpdOperatingSystemConfiguration.CENTOS_7_X86_64;
		}
		@Override
		public UnixPath getOpensslDefaultCaFile() {
			try {
				return UnixPath.valueOf("/etc/pki/tls/certs/ca-bundle.crt");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		/**
		 * This replace command is copied from /opt/mysql-5.7/bin/replace
		 * TODO: Just use sed instead.
		 */
		@Override
		public UnixPath getReplaceCommand() {
			try {
				return UnixPath.valueOf("/opt/aoserv-daemon/bin/replace");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		//@Override
		//public UnixPath getAOServClientScriptInclude() {
		//	return "/opt/aoserv-client/scripts/aoserv-client.sh";
		//}
		@Override
		public UnixPath getPostgresPath(String minorVersion) {
			try {
				return UnixPath.valueOf("/opt/postgresql-" + minorVersion);
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		//public UnixPath getMySQLConnectorJavaJarPath() {
		//	return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
		//}
		@Override
		public UnixPath getDefaultJdkSetEnv() {
			try {
				return UnixPath.valueOf("/opt/jdk1/setenv.sh");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public PackageManager.PackageName getDefaultJdkPackageName() {
			return PackageManager.PackageName.JDK1;
		}
		@Override
		public UnixPath getJdk17SetEnv() {
			try {
				return UnixPath.valueOf("/opt/jdk1.7/setenv.sh");
			} catch(ValidationException e) {
				throw new WrappedException(e);
			}
		}
		@Override
		public PackageManager.PackageName getJdk17PackageName() {
			return PackageManager.PackageName.JDK17;
		}
	},
	CENTOS_7DOM0_X86_64 {
		@Override
		public HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration() {
			return null;
		}
		@Override
		public UnixPath getOpensslDefaultCaFile() {
			return CENTOS_7_X86_64.getOpensslDefaultCaFile();
		}
		@Override
		public UnixPath getReplaceCommand() {
			return CENTOS_7_X86_64.getReplaceCommand();
		}
		//@Override
		//public UnixPath getAOServClientScriptInclude() {
		//	return "/opt/aoserv-client/scripts/aoserv-client.sh";
		//}
		@Override
		public UnixPath getPostgresPath(String minorVersion) {
			return null;
		}
		//public UnixPath getMySQLConnectorJavaJarPath() {
		//	return "/opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar";
		//}
		@Override
		public UnixPath getDefaultJdkSetEnv() {
			return CENTOS_7_X86_64.getDefaultJdkSetEnv();
		}
		@Override
		public PackageManager.PackageName getDefaultJdkPackageName() {
			return CENTOS_7_X86_64.getDefaultJdkPackageName();
		}
		@Override
		public UnixPath getJdk17SetEnv() {
			return CENTOS_7_X86_64.getJdk17SetEnv();
		}
		@Override
		public PackageManager.PackageName getJdk17PackageName() {
			return CENTOS_7_X86_64.getJdk17PackageName();
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
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.REDHAT_ES_4_X86_64 :
				return REDHAT_ES_4_X86_64;
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				return CENTOS_5_I686_AND_X86_64;
			case OperatingSystemVersion.CENTOS_5_DOM0_I686 :
				return CENTOS_5DOM0_I686;
			case OperatingSystemVersion.CENTOS_5_DOM0_X86_64 :
				return CENTOS_5DOM0_X86_64;
			case OperatingSystemVersion.CENTOS_7_X86_64 :
				return CENTOS_7_X86_64;
			case OperatingSystemVersion.CENTOS_7_DOM0_X86_64 :
				return CENTOS_7DOM0_X86_64;
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Gets the configuration for HTTP-related tasks or <code>null</code>
	 * if this OS version doesn't have any HTTP servers.
	 */
	public abstract HttpdOperatingSystemConfiguration getHttpdOperatingSystemConfiguration();

	/**
	 * Gets the default certificate authority file for OpenSSL.
	 */
	public abstract UnixPath getOpensslDefaultCaFile();

	/**
	 * Gets the full path to the replace command or <code>null</code>
	 * if unavailable.
	 */
	public abstract UnixPath getReplaceCommand();

	/**
	 * Gets the full path that a shell script would use to include the AOServ
	 * Client.
	 */
	//public abstract UnixPath getAOServClientScriptInclude();

	/**
	 * Gets the path to the PostgreSQL installation directory for the provided
	 * minor version (7.3, 8.1, 8.3, ...).
	 *
	 * @return  The full path or <code>null</code> if PostgreSQL not supported.
	 */
	public abstract UnixPath getPostgresPath(String minorVersion);

	/**
	 * Gets the full path to the preferred MySQL Java JDBC driver JAR or
	 * <code>null</code> if none available.
	 */
	//public abstract UnixPath getMySQLConnectorJavaJarPath();

	/**
	 * Gets the path to the default JDK setenv.sh script.
	 */
	public abstract UnixPath getDefaultJdkSetEnv();

	/**
	 * Gets the package that provides the default JDK.
	 */
	public abstract PackageManager.PackageName getDefaultJdkPackageName();

	/**
	 * Gets the path to the JDK 1.7 setenv.sh script.
	 */
	public abstract UnixPath getJdk17SetEnv();

	/**
	 * Gets the package that provides JDK 1.7.
	 */
	public abstract PackageManager.PackageName getJdk17PackageName();
}
