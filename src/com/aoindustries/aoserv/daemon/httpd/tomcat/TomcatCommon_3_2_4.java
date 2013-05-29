package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.daemon.util.FileUtils;

/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.io.ChainWriter;
import java.io.IOException;

/**
 * Some common code for Tomcat 3.2.4
 *
 * @author  AO Industries, Inc.
 */
public class TomcatCommon_3_2_4 extends TomcatCommon_3_X {

    private static final TomcatCommon_3_2_4 instance = new TomcatCommon_3_2_4();
    public static TomcatCommon_3_2_4 getInstance() {
        return instance;
    }

    private TomcatCommon_3_2_4() {}

    public void createWebDtd(String confDirectory, int uid, int gid, int mode) throws IOException {
        FileUtils.copyResource(TomcatCommon_3_2_4.class, "web.dtd-3.2.4", confDirectory+"/web.dtd", uid, gid, mode);
    }

    public void createWebXml(String confDirectory, int uid, int gid, int mode) throws IOException {
        FileUtils.copyResource(TomcatCommon_3_2_4.class, "web.xml-3.2.4", confDirectory+"/web.xml", uid, gid, mode);
    }

    public void printTomcatUsers(ChainWriter out) {
        out.print("<tomcat-users>\n"
                + "  <user name=\"tomcat\" password=\"tomcat\" roles=\"tomcat\" />\n"
                + "  <user name=\"role1\"  password=\"tomcat\" roles=\"role1\" />\n"
                + "  <user name=\"both\"   password=\"tomcat\" roles=\"tomcat,role1\" />\n"
                + "</tomcat-users>\n");
    }

    String getServletApiVersion() {
        return "3.2";
    }

    String getTomcatApiVersion() {
        return "3.2";
    }
}
