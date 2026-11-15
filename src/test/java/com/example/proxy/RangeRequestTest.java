package com.example.proxy;

import com.example.backend.RangeRequest;

public final class RangeRequestTest {
    private static void test(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        test(RangeRequest.select(null,100).length()==100,"full");
        test(RangeRequest.select("bytes=0-9",100).length()==10,"prefix");
        test(RangeRequest.select("bytes=-10",100).start()==90,"suffix");
        test(RangeRequest.select("bytes=90-",100).end()==99,"open-ended");
        test(RangeRequest.select("bytes=101-",100).status()==416,"unsatisfiable");
        test(RangeRequest.select("bytes=0-1,4-5",100).status()==416,"multipart unsupported");
        test(RangeRequest.select("bytes=5-4",100).status()==416,"reverse");
        test(RangeRequest.select("bytes=0-999999999999999999999",100).status()==416,"overflow");
        test(RangeRequest.select("bytes=-0",100).status()==416,"zero suffix");
        System.out.println("RangeRequestTest PASS");
    }
}
