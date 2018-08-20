/*
 * Copyright 2008-2013, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.util.CalendarUtils;
import java.io.IOException;
import java.sql.SQLException;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Set;

/**
 * Some common code for all installations of Tomcat.
 *
 * @author  AO Industries, Inc.
 */
public abstract class TomcatCommon {

	private static final String BACKUP_DATE_SEPARATOR = "-";
	public static final String BACKUP_SEPARATOR = ".";
	public static final String BACKUP_EXTENSION = ".bak";

	/**
	 * Gets the suffix to put after an existing file, but before the extension.
	 */
	public static String getBackupSuffix() {
		return BACKUP_DATE_SEPARATOR + CalendarUtils.formatDate(new GregorianCalendar(Locale.ROOT));
	}

	TomcatCommon() {
	}

	/**
	 * Gets any packages that must be installed for this site.
	 */
	protected abstract Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException;

	/**
     * Writes a single parameter.
     */
    public void writeHttpdTomcatParameter(HttpdTomcatParameter parameter, ChainWriter out) throws IOException {
        out.print("          <Parameter\n"
                + "            name=\"").encodeXmlAttribute(parameter.getName()).print("\"\n"
                + "            value=\"").encodeXmlAttribute(parameter.getValue()).print("\"\n"
                + "            override=\"").encodeXmlAttribute(parameter.getOverride()).print("\"\n");
        if(parameter.getDescription()!=null) out.print("            description=\"").encodeXmlAttribute(parameter.getDescription()).print("\"\n");
        out.print("          />\n");
    }

    /**
     * Writes a single data source.
     */
    public abstract void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws IOException, SQLException;
}
