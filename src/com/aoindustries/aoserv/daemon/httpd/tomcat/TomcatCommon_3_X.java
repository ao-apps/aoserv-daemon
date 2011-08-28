package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * Some common code for Tomcat 3.X
 *
 * @author  AO Industries, Inc.
 */
public abstract class TomcatCommon_3_X extends TomcatCommon {

    TomcatCommon_3_X() {}

    /**
     * The list of files that are contained in /www/{site}/var/log directories.
     */
    public static final String[] tomcatLogFiles={
        "jasper.log",
        "jvm_crashes.log",
        "servlet.log",
        "servlet_err",
        "tomcat.log"
    };

    public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        throw new SQLException("Not implemented for Tomcat 3.X");
    }

    /**
     * Uses os-default JDK.
     * 
     * @see  OperatingSystemConfiguration#getDefaultJdkVersion
     */
    public String getDefaultJdkVersion() throws IOException, SQLException {
        return OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkVersion();
    }
    
    /**
     * Creates the test-tomcat.xml file in the provided conf directory.
     */
    public void createTestTomcatXml(String confDirectory, int uid, int gid, int mode) throws IOException {
        FileUtils.copyResource(TomcatCommon_3_X.class, "test-tomcat.xml", confDirectory+"/test-tomcat.xml", uid, gid, 0660);
    }

    public abstract void createWebDtd(String confDirectory, int uid, int gid, int mode) throws IOException;

    public abstract void createWebXml(String confDirectory, int uid, int gid, int mode) throws IOException;

    public void copyCocoonProperties1(OutputStream out) throws IOException {
        FileUtils.copyResource(TomcatCommon_3_X.class, "cocoon.properties.1", out);
    }

    public void copyCocoonProperties2(OutputStream out) throws IOException {
        FileUtils.copyResource(TomcatCommon_3_X.class, "cocoon.properties.2", out);
    }
    
    /**
     * Prints the default tomcat-users.xml file content for this version of Tomcat 3.X
     */
    public abstract void printTomcatUsers(ChainWriter out);
    
    /**
     * Gets the servlet API version.
     */
    abstract String getServletApiVersion();
    
    /**
     * Gets the tomcat API version.
     */
    abstract String getTomcatApiVersion();
}
