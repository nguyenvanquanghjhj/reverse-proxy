package com.example.backend;

import com.example.web.PageTemplate;

import java.time.Instant;
import java.util.Locale;

/** Self-contained request playground. Network traffic is initiated only by explicit user actions. */
final class WorkloadConsole {
    private WorkloadConsole() { }

    static String render(int port, int capacity, int active, int queued, int delayMs,
                         long requestNo, long heapBytes, boolean viaProxy) {
        int theme = Math.floorMod(port - 9001, 3);
        String number = port >= 9001 && port <= 9003 ? "0" + (port - 9000) : Integer.toString(port);
        String[] names = {"Aurora", "Ember", "Orbit"};
        String[] colors = {"#087f67", "#b94b1d", "#7051c2"};
        String[] bright = {"#8ef0ca", "#ffbf86", "#c8b2ff"};
        String[] soft = {"#e0f4ea", "#fff0e1", "#eee8fb"};
        return PAGE.replace("@@NUMBER@@", number).replace("@@NAME@@", names[theme])
                .replace("@@ACCENT@@", colors[theme]).replace("@@BRIGHT@@", bright[theme]).replace("@@SOFT@@", soft[theme])
                .replace("@@PORT@@", Integer.toString(port))
                .replace("@@PID@@", Long.toString(ProcessHandle.current().pid()))
                .replace("@@CAPACITY@@", Integer.toString(capacity)).replace("@@ACTIVE@@", Integer.toString(active))
                .replace("@@QUEUED@@", Integer.toString(queued)).replace("@@DELAY@@", Integer.toString(delayMs))
                .replace("@@REQUEST@@", Long.toString(requestNo)).replace("@@TIME@@", Instant.now().toString())
                .replace("@@HEAP@@", String.format(Locale.ROOT, "%.1f", heapBytes / 1048576.0))
                .replace("@@VIA@@", Boolean.toString(viaProxy));
    }

    private static String pdfScript() {
        try(var in=WorkloadConsole.class.getResourceAsStream("/web/pdf-lab.js")) {
            if(in!=null)return new String(in.readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);
        }catch(java.io.IOException ignored){}
        return "";
    }
    private static final String PAGE = PageTemplate.load("/web/workload.html").replace("%PDF_LAB_SCRIPT%",pdfScript());
}
