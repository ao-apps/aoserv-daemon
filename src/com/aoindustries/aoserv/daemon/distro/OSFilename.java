package com.aoindustries.aoserv.daemon.distro;

/*
 * Copyright 2003-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;

/**
 * Used by DistroGenerator.
 *
 * @author  AO Industries, Inc.
 */
public final class OSFilename {
    
    public int operating_system_version;
    public String filename;

    public void parseValues(String path) {
        if(path.length()>0 && path.charAt(0)=='/') path=path.substring(1);

        int pos1=path.indexOf('/');
        if(pos1==-1) {
            operating_system_version=-1;
            filename=null;
            return;
        }
        
        String osName=path.substring(0, pos1);
        int pos2=path.indexOf('/', pos1+1);
        if(pos2==-1) {
            operating_system_version=-1;
            filename=null;
            return;
        }
        
        String osVersion=path.substring(pos1+1, pos2);
        int pos3=path.indexOf('/', pos2+1);
        if(pos3==-1) {
            String osArchitecture=path.substring(pos2+1);
            operating_system_version=DistroGenerator.getOperatingSystemVersion(osName, osVersion, osArchitecture);
            filename="/";
            return;
        }
        
        String osArchitecture=path.substring(pos2+1, pos3);
        operating_system_version=DistroGenerator.getOperatingSystemVersion(osName, osVersion, osArchitecture);
        filename=path.substring(pos3);
    }

    public String toString() {
        return getOSName()+','+getOSVersion()+','+getOSArchitecture()+','+filename;
    }
    
    public String getOSName() {
        switch(operating_system_version) {
            case OperatingSystemVersion.MANDRIVA_2006_0_I586: return OperatingSystem.MANDRIVA;
            case OperatingSystemVersion.REDHAT_ES_4_X86_64: return OperatingSystem.REDHAT;
            case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64: return OperatingSystem.CENTOS;
            default: throw new RuntimeException("Unsupported operating_system_version: "+operating_system_version);
        }
    }

    public String getOSVersion() {
        switch(operating_system_version) {
            case OperatingSystemVersion.MANDRIVA_2006_0_I586: return OperatingSystemVersion.VERSION_2006_0;
            case OperatingSystemVersion.REDHAT_ES_4_X86_64: return OperatingSystemVersion.VERSION_ES_4;
            case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64: return OperatingSystemVersion.VERSION_5;
            default: throw new RuntimeException("Unsupported operating_system_version: "+operating_system_version);
        }
    }

    public String getOSArchitecture() {
        switch(operating_system_version) {
            case OperatingSystemVersion.MANDRIVA_2006_0_I586: return Architecture.I586;
            case OperatingSystemVersion.REDHAT_ES_4_X86_64: return Architecture.X86_64;
            case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64: return Architecture.I686_AND_X86_64;
            default: throw new RuntimeException("Unsupported operating_system_version: "+operating_system_version);
        }
    }
    
    public String getFullPath(String root) {
        return root+'/'+getOSName()+'/'+getOSVersion()+'/'+getOSArchitecture()+filename;
    }
}