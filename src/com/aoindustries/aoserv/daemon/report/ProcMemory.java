package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;

/**
 * Encapsulates the output of the /proc/meminfo file.
 *
 * @author  AO Industries, Inc.
 */
final public class ProcMemory {

    public final int
        mem_total,
        mem_free,
        mem_shared,
        buffers,
        cached,
        swap_cached,
        active,
        inact_dirty,
        inact_clean,
        inact_target,
        high_total,
        high_free,
        low_total,
        low_free
    ;

    public ProcMemory() throws IOException, SQLException {
        int
            mem_total=0,
            mem_free=0,
            mem_shared=0,
            buffers=0,
            cached=0,
            swap_cached=0,
            active=0,
            inact_dirty=0,
            inact_clean=0,
            inact_target=0,
            high_total=0,
            high_free=0,
            low_total=0,
            low_free=0
        ;

        // Only the outer-most server tracks memory use
        if(AOServDaemon.getThisAOServer().getFailoverServer()==null) {
            // Parse for the values
            BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo")));
            try {
                String line;
                while((line=in.readLine())!=null) {
                    String[] words=StringUtility.splitString(line);
                    String label=words[0];
                    if(label.equals("MemTotal:")) mem_total=Integer.parseInt(words[1]);
                    else if(label.equals("MemFree:")) mem_free=Integer.parseInt(words[1]);
                    else if(label.equals("MemShared:")) mem_shared=Integer.parseInt(words[1]);
                    else if(label.equals("Buffers:")) buffers=Integer.parseInt(words[1]);
                    else if(label.equals("Cached:")) cached=Integer.parseInt(words[1]);
                    else if(label.equals("SwapCached:")) swap_cached=Integer.parseInt(words[1]);
                    else if(label.equals("Active:")) active=Integer.parseInt(words[1]);
                    else if(label.equals("Inact_dirty:")) inact_dirty=Integer.parseInt(words[1]);
                    else if(label.equals("Inact_clean:")) inact_clean=Integer.parseInt(words[1]);
                    else if(label.equals("Inact_target:")) inact_target=Integer.parseInt(words[1]);
                    else if(label.equals("HighTotal:")) high_total=Integer.parseInt(words[1]);
                    else if(label.equals("HighFree:")) high_free=Integer.parseInt(words[1]);
                    else if(label.equals("LowTotal:")) low_total=Integer.parseInt(words[1]);
                    else if(label.equals("LowFree:")) low_free=Integer.parseInt(words[1]);
                }
            } finally {
                in.close();
            }
        }

        // Copy into instance
        this.mem_total=mem_total;
        this.mem_free=mem_free;
        this.mem_shared=mem_shared;
        this.buffers=buffers;
        this.cached=cached;
        this.swap_cached=swap_cached;
        this.active=active;
        this.inact_dirty=inact_dirty;
        this.inact_clean=inact_clean;
        this.inact_target=inact_target;
        this.high_total=high_total;
        this.high_free=high_free;
        this.low_total=low_total;
        this.low_free=low_free;
    }

    public static void main(String[] args) {
        try {
            System.err.println(new ProcMemory());
            System.exit(0);
        } catch(IOException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(1);
        } catch(SQLException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(2);
        }
    }

    public String toString() {
        return
            getClass().getName()
            + "?mem_total="+mem_total
            + "&mem_free="+mem_free
            + "&mem_shared="+mem_shared
            + "&buffers="+buffers
            + "&cached="+cached
            + "&swap_cached="+swap_cached
            + "&active="+active
            + "&inact_dirty="+inact_dirty
            + "&inact_clean="+inact_clean
            + "&inact_target="+inact_target
            + "&high_total="+high_total
            + "&high_free="+high_free
            + "&low_total="+low_total
            + "&low_free="+low_free
        ;
    }
}
