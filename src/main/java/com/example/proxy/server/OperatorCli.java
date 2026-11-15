package com.example.proxy.server;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Local-only management utility; use inside proxy JVM container or same host. */
public final class OperatorCli {
    public static void main(String[] args)throws Exception {
        if(args.length<1||args.length>2)throw new IllegalArgumentException("metrics|strategy|drain <backend-id>|resume <backend-id>");
        String action=args[0];
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/__proxy/" +
                (action.equals("metrics")?"metrics":action.equals("strategy")?"strategy":"backend-drain")))
                .timeout(Duration.ofSeconds(3));
        if(action.equals("metrics"))builder.GET();
        else {
            String token=System.getenv("PBL4_CONTROL_TOKEN");
            if(token!=null&&!token.isBlank())builder.header("X-PBL4-Control-Token",token);
            if(action.equals("strategy"))builder.header("X-Proxy-Action","cycle-strategy");
            else if(action.equals("drain")||action.equals("resume")){
                if(args.length!=2||!args[1].matches("[A-Za-z0-9.-]+:[0-9]{1,5}"))
                    throw new IllegalArgumentException("Supply backend-id, e.g. backend1:9001");
                builder.header("X-Proxy-Action","set-backend-draining")
                        .header("X-Proxy-Target",args[1])
                        .header("X-Proxy-Draining",Boolean.toString(action.equals("drain")));
            } else throw new IllegalArgumentException("Unknown operation: "+action);
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        var response=HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP "+response.statusCode()+"\n"+response.body());
        if(response.statusCode()>=400)System.exit(1);
    }
}
