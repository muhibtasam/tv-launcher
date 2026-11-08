package io.vyro.tv;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The page a phone opens from the QR code in Home Settings > Backgrounds, to send photos for Home's background:
 * http://<the TV's address on the home network>:<a port the system picks>/<a one-time random token>, as KeyPage's.
 * The phone picks photos, its browser shrinks each to 4K (a JPEG), and sends them one by one (a POST each, the bytes
 * as the body); the TV keeps each one that decodes (Backgrounds.add). Up to MAX_PHOTOS, for 5 minutes; plain HTTP on the
 * LAN address only, as KeyPage. Nothing leaves the home network.
 */
final class BgPage {
    /** Called on the page's own threads, never the UI's. */
    interface Listener {
        /** A photo was saved (`sent` so far from this page). */
        void added(int sent);
        /** The page stopped: after 5 minutes (expired), MAX_PHOTOS, or close(). Called once. */
        void closed(BgPage page, boolean expired);
    }

    static final int MAX_PHOTOS = 20;
    private static final int MAX_REQUESTS = 200, MAX_ANSWERING = 3;
    private static final long READ_FOR_NS = 15_000_000_000L;   // a request's head; the photo itself has the socket's timeout

    final String url;
    private final Context app;
    private final ServerSocket server;
    private final String path;
    private final Listener listener;
    private final AtomicInteger sent = new AtomicInteger(), answering = new AtomicInteger();
    private volatile boolean closed;

    BgPage(Context c, InetAddress lan, Listener listener) throws IOException {
        app = c.getApplicationContext();
        this.listener = listener;
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = new ServerSocket(0, 4, lan);
        url = "http://" + lan.getHostAddress() + ":" + server.getLocalPort() + path;
        Thread t = new Thread(this::serve, "BgPage");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        long until = System.nanoTime() + KeyPage.OPEN_FOR * 1_000_000L;
        boolean expired = false;
        int requests = 0;
        try {
            while (!closed && sent.get() < MAX_PHOTOS) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0 || requests >= MAX_REQUESTS) {
                    expired = true;
                    break;
                }
                server.setSoTimeout((int) Math.min(left, 30_000));
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                requests++;
                if (answering.incrementAndGet() > MAX_ANSWERING) {
                    answering.decrementAndGet();
                    socket.close();
                    continue;
                }
                Thread t = new Thread(() -> answer(socket), "BgPage answer");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (!closed) AppLog.w("backgrounds page: " + e.getMessage());
        } finally {
            close();
            listener.closed(this, expired);
        }
    }

    private void answer(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15_000);
            handle(s.getInputStream(), s.getOutputStream());
        } catch (IOException | RuntimeException e) {
            // A phone that went away midway: the page stays open. Nothing may escape this thread (:home's watchdog).
        } finally {
            answering.decrementAndGet();
        }
    }

    private void handle(InputStream in, OutputStream out) throws IOException {
        String head = KeyPage.readHead(in, System.nanoTime() + READ_FOR_NS);
        if (head == null) return;
        String[] line = head.substring(0, Math.max(0, head.indexOf("\r\n"))).split(" ");
        String method = line.length > 1 ? line[0] : "", target = line.length > 1 ? line[1] : "";
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        if (!target.equals(path)) {
            KeyPage.respond(out, "404 Not Found", "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (method.equals("GET") || method.equals("HEAD")) {
            KeyPage.respond(out, "200 OK", "text/html; charset=utf-8", method.equals("HEAD") ? new byte[0] : page());
            return;
        }
        if (!method.equals("POST")) {
            KeyPage.respond(out, "405 Method Not Allowed", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        int length = KeyPage.contentLength(head);
        if (closed || sent.get() >= MAX_PHOTOS) {
            text(out, "409 Conflict", Ui.tr("This code has expired."));
            return;
        }
        if (length <= 0 || length > Backgrounds.MAX_BYTES) {
            text(out, "413 Payload Too Large", Ui.tr("That photo couldn't be read."));
            return;
        }
        String why = Backgrounds.add(app, in, length, ".jpg");
        if (why != null) {
            text(out, "400 Bad Request", Ui.tr("That photo couldn't be read."));
            return;
        }
        int n = sent.incrementAndGet();
        listener.added(n);
        text(out, "200 OK", Ui.plural("plural|one:{n} photo sent to the TV.|other:{n} photos sent to the TV.", n));
    }

    private static void text(OutputStream out, String status, String message) throws IOException {
        KeyPage.respond(out, status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
    }

    /** The photos to pick, their previews, the button, and the script that shrinks and sends them. */
    private byte[] page() {
        StringBuilder b = new StringBuilder(4096).append("<!doctype html><html lang=\"").append(KeyPage.esc(Ui.language))
                .append("\"><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">")
                .append("<title>VYRO</title><style>")
                .append("body{margin:0 auto;max-width:34em;padding:24px;font:17px/1.5 system-ui,-apple-system,sans-serif;")
                .append("background:#140e0c;color:#f3ede2}h1{font-size:26px;margin:.2em 0}")
                .append("button,.b{display:block;box-sizing:border-box;width:100%;margin:14px 0;padding:16px;border:0;")
                .append("border-radius:14px;background:#f3ede2;color:#1a1612;font:600 17px system-ui,sans-serif;text-align:center}")
                .append(".b{background:transparent;color:#f3ede2;border:1.5px dashed rgba(243,237,226,.4);padding:30px 16px}")
                .append("button:disabled{opacity:.4}#g{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}")
                .append("#g img{width:100%;aspect-ratio:1;object-fit:cover;border-radius:10px}")
                .append(".s{color:#a8a29c;font-size:15px}.ok{color:#7ee08a;font-size:19px}.no{color:#ffc266;font-size:19px}</style>")
                .append("<h1>").append(KeyPage.esc(Ui.tr("Backgrounds"))).append(" · VYRO</h1><p>")
                .append(KeyPage.esc(Ui.tr("Pick photos: they go straight to the TV over your Wi-Fi."))).append("</p>")
                .append("<input type=file id=f accept=\"image/*\" multiple hidden>")
                .append("<label for=f class=b>＋ ").append(KeyPage.esc(Ui.tr("Choose photos"))).append("</label>")
                .append("<div id=g></div><button id=s disabled>").append(KeyPage.esc(Ui.tr("Send to the TV"))).append("</button>")
                .append("<p id=m class=s>").append(KeyPage.esc(Ui.tr("The photos stay on your network. This page works for 5 minutes.")))
                .append("</p><script>")
                .append("const S=").append(js(Ui.tr("Sending…"))).append(",E=").append(js(Ui.tr("That photo couldn't be read.")))
                .append(",M=").append(MAX_PHOTOS).append(";")
                .append("const f=document.getElementById('f'),g=document.getElementById('g'),s=document.getElementById('s'),")
                .append("m=document.getElementById('m');let p=[];")
                .append("f.onchange=()=>{p=[...f.files].slice(0,M);g.innerHTML='';for(const x of p){const i=new Image();")
                .append("i.alt='';i.src=URL.createObjectURL(x);g.appendChild(i)}s.disabled=!p.length};")
                // Each photo at 3840 on its long side at most, as a JPEG: quick to send, and enough for a 4K TV.
                .append("async function k(x){try{const b=await createImageBitmap(x);const r=Math.min(1,3840/Math.max(b.width,b.height));")
                .append("const c=document.createElement('canvas');c.width=Math.round(b.width*r);c.height=Math.round(b.height*r);")
                .append("c.getContext('2d').drawImage(b,0,0,c.width,c.height);return await new Promise(o=>c.toBlob(o,'image/jpeg',.9))}")
                .append("catch(e){return x}}")
                .append("s.onclick=async()=>{s.disabled=true;f.disabled=true;m.className='s';m.textContent=S;")
                .append("for(const x of p){let r;try{r=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':'image/jpeg'},")
                .append("body:await k(x)})}catch(e){m.className='no';m.textContent=E;break}m.className=r.ok?'ok':'no';")
                .append("m.textContent=await r.text();if(r.status==409)break}p=[];g.innerHTML='';f.disabled=false};")
                .append("</script></html>");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A JavaScript string literal (no "</script>" can end the script early). */
    private static String js(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '<') b.append("\\u003c");
            else if (ch == '\n') b.append("\\n");
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "BgPage(port %d)", server.getLocalPort());   // never the token
    }
}
