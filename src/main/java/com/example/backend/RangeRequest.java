package com.example.backend;

/** Strict single-byte-range parser for a known-size immutable representation. */
public final class RangeRequest {
    private RangeRequest() {}
    public record Selection(int status, long start, long end, String contentRange) {
        public long length() { return status == 416 ? 0 : end - start + 1; }
    }
    public static Selection select(String value, long size) {
        if (size < 1) throw new IllegalArgumentException("File must be nonempty");
        if (value == null) return new Selection(200, 0, size - 1, null);
        if (!value.startsWith("bytes=") || value.length()>100 || value.indexOf(',')!=-1)
            return unsatisfied(size);
        String s=value.substring(6);
        int hyphen=s.indexOf('-');
        if (hyphen<0 || s.indexOf('-',hyphen+1)>=0) return unsatisfied(size);
        String a=s.substring(0,hyphen), b=s.substring(hyphen+1);
        if (a.isEmpty() && b.isEmpty()) return unsatisfied(size);
        if (!a.isEmpty()&&!a.matches("[0-9]{1,19}")) return unsatisfied(size);
        if (!b.isEmpty()&&!b.matches("[0-9]{1,19}")) return unsatisfied(size);
        try {
            long start,end;
            if (a.isEmpty()) {
                long suffix=Long.parseLong(b);
                if(suffix==0) return unsatisfied(size);
                start=size-Math.min(size,suffix);end=size-1;
            } else {
                start=Long.parseLong(a);
                if(start>=size) return unsatisfied(size);
                end=b.isEmpty()?size-1:Math.min(size-1,Long.parseLong(b));
                if(end<start) return unsatisfied(size);
            }
            return new Selection(206,start,end,"bytes "+start+"-"+end+"/"+size);
        } catch (NumberFormatException bad) { return unsatisfied(size); }
    }
    private static Selection unsatisfied(long size) { return new Selection(416,0,-1,"bytes */"+size); }
}
