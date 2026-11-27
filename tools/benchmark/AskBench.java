package ae.fieldguide;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Standalone Android shell benchmark. Uses the shipping retriever and local-only llama.cpp server. */
public final class AskBench {
    static final File root = new File("/data/local/tmp/field-ask123");
    static final String base = "http://127.0.0.1:18437";
    static PrintWriter out;
    static long now() { return SystemClock.elapsedRealtime(); }
    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static JSONObject request(String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(base+path).openConnection();
        c.setConnectTimeout(1200); c.setReadTimeout(180000);
        try {
            if (body!=null) {
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                try (OutputStream stream=c.getOutputStream()) {stream.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            }
            InputStream stream=c.getResponseCode()==200 ? c.getInputStream() : c.getErrorStream();
            try (Scanner scan=new Scanner(stream,"UTF-8").useDelimiter("\\A")) { return new JSONObject(scan.next()); }
        } finally { c.disconnect(); }
    }
    static void emit(JSONObject event) {out.println(event);out.flush();}
    static void block(String model, String run, SemanticIndex index, JSONArray cases, JSONObject descriptions, boolean smoke) throws Exception {
        File bin=new File(root,"bin");
        long before=now();
        ProcessBuilder builder=new ProcessBuilder(
            new File(bin,"llama-server").getAbsolutePath(),"-m",new File(root,model+".gguf").getAbsolutePath(),
            "-c","1536","-b","256","-t","2","-tb","2","-ngl","0","-np","1","--cache-ram","0",
            "--no-warmup","--no-mmproj","--reasoning","off","--host","127.0.0.1","--port","18437");
        builder.directory(bin);builder.environment().put("LD_LIBRARY_PATH",bin.getAbsolutePath());
        builder.redirectErrorStream(true).redirectOutput(new File(root,"server-"+run+".log"));
        Process server=builder.start();
        try {
            boolean ready=false;
            for(int i=0;i<180 && server.isAlive();i++) {
                try {ready="ok".equals(request("/health",null).optString("status"));}catch(Exception ignored){}
                if(ready)break;
                Thread.sleep(500);
            }
            if(!ready)throw new IOException("Model did not start; inspect server-"+run+".log");
            long loadMs=now()-before;
            long blockStart=now();
            for(int i=0;i<(smoke?1:cases.length());i++) {
                JSONObject test=smoke ? new JSONObject().put("id","smoke").put("question","Which stars point to the North Star?") : cases.getJSONObject(i);
                String question=test.getString("question");
                long t=System.nanoTime();
                List<SemanticIndex.Hit> hits=index.search(question,4);
                double searchMs=(System.nanoTime()-t)/1e6;
                JSONArray keys=new JSONArray(), scores=new JSONArray();
                List<String> choices=new ArrayList<>();
                for(int j=0;j<hits.size();j++){
                    SemanticIndex.Hit h=hits.get(j);keys.put(h.key);scores.put((double)h.score);
                    choices.add(descriptions.getString(h.key));
                }
                JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",PassagePrompt.SYSTEM))
                    .put(new JSONObject().put("role","user").put("content",PassagePrompt.question(choices,question)));
                JSONObject req=new JSONObject().put("messages",messages).put("temperature",0).put("max_tokens",1)
                    .put("grammar","root ::= [0-4]").put("cache_prompt",false).put("seed",42);
                JSONObject response=request("/v1/chat/completions",req);
                long elapsed=Math.round((System.nanoTime()-t)/1e6);
                if(!response.has("choices"))throw new IOException(response.toString());
                String label=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim();
                if(!label.matches("[0-4]"))throw new IOException("Invalid model label: "+label);
                int selected=Integer.parseInt(label);
                emit(new JSONObject().put("event","query").put("run",run).put("model",model).put("id",test.getString("id"))
                    .put("keys",keys).put("scores",scores).put("chosen",selected==0?JSONObject.NULL:keys.get(selected-1))
                    .put("ms",elapsed).put("search_ms",searchMs));
            }
            emit(new JSONObject().put("event","block").put("run",run).put("model",model)
                .put("load_ms",loadMs).put("ms",now()-blockStart));
        } finally {server.destroy();if(!server.waitFor(4,java.util.concurrent.TimeUnit.SECONDS))server.destroyForcibly();}
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[1].equals("usb") || args[1].equals("smoke"))) {
            throw new IllegalArgumentException("Expected RUN_NAME usb|smoke MODEL [MODEL ...]");
        }
        String tag=args[0];
        boolean smoke=args[1].equals("smoke");
        try(PrintWriter writer=new PrintWriter(new File(root,tag+".jsonl"),"UTF-8")) {
            out=writer;
            SemanticIndex index=new SemanticIndex(new FileInputStream(new File(root,"semantic.bin")));
            JSONArray cases=new JSONArray(read(new File(root,"cases.json")));
            JSONObject descriptions=new JSONObject(read(new File(root,"descriptions.json")));
            for(int i=2;i<args.length;i++)block(args[i],tag+"-"+i,index,cases,descriptions,smoke);
            emit(new JSONObject().put("event","done"));
        } catch(Exception e){e.printStackTrace();System.exit(1);}
    }
}
