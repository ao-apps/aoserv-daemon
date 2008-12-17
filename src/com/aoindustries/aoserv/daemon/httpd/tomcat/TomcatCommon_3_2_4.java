package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */

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
}
