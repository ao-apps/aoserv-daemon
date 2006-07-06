package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.io.AOPool;
import com.aoindustries.profiler.*;
import java.io.*;
import java.util.*;

/**
 * The configuration for all AOServ processes is stored in a properties file.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonConfiguration {

    private static Properties props;

    private static String getProperty(String name) throws IOException {
        Profiler.startProfile(Profiler.IO, AOServDaemonConfiguration.class, "getProperty(String)", null);
        try {
	    synchronized(AOServDaemonConfiguration.class) {
		Properties newProps = new Properties();
		if (props == null) {
		    InputStream in = new BufferedInputStream(AOServDaemonConfiguration.class.getResourceAsStream("aoserv-daemon.properties"));
		    try {
			newProps.load(in);
		    } finally {
			in.close();
		    }
		    props = newProps;
		}
		return props.getProperty("aoserv.daemon."+name);
	    }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
    
    public static int getProfilerLevel() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getProfilerLevel()", null);
        try {
            return Profiler.parseProfilerLevel(getProperty("profiler.level"));
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static boolean isNested() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "isNested()", null);
        try {
            return "true".equalsIgnoreCase(getProperty("nested"));
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getErrorEmailTo() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getErrorEmailTo()", null);
        try {
            return getProperty("error.email.to");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getErrorEmailFrom() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getErrorEmailFrom()", null);
        try {
            return getProperty("error.email.from");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getErrorSmtpServer() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getErrorSmtpServer()", null);
        try {
            return getProperty("error.smtp.server");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getWarningEmailTo() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getWarningEmailTo()", null);
        try {
            return getProperty("warning.email.to");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getWarningEmailFrom() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getWarningEmailFrom()", null);
        try {
            return getProperty("warning.email.from");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getWarningSmtpServer() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getWarningSmtpServer()", null);
        try {
            return getProperty("warning.smtp.server");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getSecurityEmailTo() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getSecurityEmailTo()", null);
        try {
            return getProperty("security.email.to");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getSecurityEmailFrom() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getSecurityEmailFrom()", null);
        try {
            return getProperty("security.email.from");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getSecuritySmtpServer() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getSecuritySmtpServer()", null);
        try {
            return getProperty("security.smtp.server");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMonitorEmailFullTo() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMonitorEmailFullTo()", null);
        try {
            return getProperty("monitor.email.full.to");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMonitorEmailFullFrom() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMonitorEmailFullFrom()", null);
        try {
            return getProperty("monitor.email.full.from");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMonitorEmailSummaryTo() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMonitorEmailSummaryTo()", null);
        try {
            return getProperty("monitor.email.summary.to");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMonitorEmailSummaryFrom() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMonitorEmailSummaryFrom()", null);
        try {
            return getProperty("monitor.email.summary.from");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMonitorSmtpServer() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMonitorSmtpServer()", null);
        try {
            return getProperty("monitor.smtp.server");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getServerHostname() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getServerHostname()", null);
        try {
            return getProperty("server.hostname");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getSSLKeystorePassword() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getSSLKeystorePassword()", null);
        try {
            return getProperty("ssl.keystore.password");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getSSLKeystorePath() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getSSLKeystorePath()", null);
        try {
            return getProperty("ssl.keystore.path");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Gets the maximum backup bandwidth in bits per second or <code>-1</code> if unlimited.
     */
    public static int getBackupBandwidthLimit() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getBackupBandwidthLimit()", null);
        try {
            String S=getProperty("backup.bandwidth.limit");
            return S==null || S.length()==0 ? -1 : Integer.parseInt(S);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static int getBackupReplicatorConcurrency() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getBackupReplicatorConcurrency()", null);
        try {
            return Integer.parseInt(getProperty("backup.replicator.concurrency"));
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getBackupReplicatorPath() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getBackupReplicatorPath()", null);
        try {
            return getProperty("backup.replicator.path");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getPostgresPassword() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getPostgresPassword()", null);
        try {
            return getProperty("postgres.password");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static int getPostgresConnections() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getPostgresConnections()", null);
        try {
            return Integer.parseInt(getProperty("postgres.connections"));
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static long getPostgresMaxConnectionAge() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getPostgresMaxConnectionAge()", null);
        try {
            String S=getProperty("postgres.max_connection_age");
            return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMySqlDriver() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMySqlDriver()", null);
        try {
            return getProperty("mysql.driver");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMySqlUser() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMySqlUser()", null);
        try {
            return getProperty("mysql.user");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getMySqlPassword() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMySqlPassword()", null);
        try {
            return getProperty("mysql.password");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static int getMySqlConnections() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMySqlConnections()", null);
        try {
            return Integer.parseInt(getProperty("mysql.connections"));
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static long getMySqlMaxConnectionAge() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getMySqlMaxConnectionAge()", null);
        try {
            String S=getProperty("mysql.max_connection_age");
            return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getInterBasePassword() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getInterBasePassword()", null);
        try {
            return getProperty("interbase.password");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static int getInterBaseConnections() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getInterBaseConnections()", null);
        try {
            return Integer.parseInt(getProperty("interbase.connections"));
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static long getInterBaseMaxConnectionAge() throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "getInterBaseMaxConnectionAge()", null);
        try {
            String S=getProperty("interbase.max_connection_age");
            return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static boolean isManagerEnabled(Class clazz) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonConfiguration.class, "isManagerEnabled(Class)", null);
        try {
            final String stripPrefix="com.aoindustries.aoserv.daemon.";
            String key=clazz.getName();
            if(key.startsWith(stripPrefix)) key=key.substring(stripPrefix.length());
            key=key+".enabled";
            String value=getProperty(key);
            if("true".equalsIgnoreCase(value)) return true;
            if("false".equalsIgnoreCase(value)) return false;
            throw new IOException("Value in aoserv-daemon.properties must be either \"true\" or \"false\": "+key);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}
