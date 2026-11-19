package com.example.proxy;
import com.example.backend.PdfLab;
import java.net.InetAddress;
public final class PdfLabSecurityTest {
    public static void main(String[] args)throws Exception {
        for(String ip:new String[]{"0.0.0.0","127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","198.18.0.1","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","2002:a00:1::1"})
            if(PdfLab.publicAddress(InetAddress.getByName(ip)))throw new AssertionError("unsafe source: "+ip);
        if(!PdfLab.publicAddress(InetAddress.getByName("8.8.8.8")))throw new AssertionError("public IPv4 rejected");
        var verified=InetAddress.getByName("8.8.8.8");
        var rebound=new java.net.InetSocketAddress(InetAddress.getByName("169.254.169.254"),443);
        if(!PdfLab.pinnedDestination(verified,rebound).getAddress().equals(verified))throw new AssertionError("initial TCP connect must ignore rebound DNS address");
        try{PdfLab.pinnedDestination(InetAddress.getByName("127.0.0.1"),rebound);throw new AssertionError("private pin accepted");}catch(java.io.IOException expected){}
        try(var lab=new PdfLab(false)){
            if(lab.begin("w3c-dummy")||lab.begin("https://127.0.0.1/private"))throw new AssertionError("disabled source accepted");
            if(!lab.status().contains("\"enabled\":false"))throw new AssertionError("disabled state missing");
        }
        System.out.println("PdfLabSecurityTest PASS: opt-in, fixed source and private/link-local/metadata/multicast/transition address rejection");
    }
}
