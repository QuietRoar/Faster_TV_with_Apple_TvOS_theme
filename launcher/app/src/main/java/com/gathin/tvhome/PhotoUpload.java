package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

/**
 * Photos from a phone on the same Wi-Fi, for themes of your own: a tiny web page the phone opens (the address and
 * a QR code are on the TV), which shrinks each photo to at most 2560 px and sends it here. It runs only while
 * Settings' "Add from your phone" page is open, answers only addresses on the home network, takes only JPEG, PNG
 * and WebP of at most 30 MB, and saves them to the launcher's own mythemes folder, as a USB drive's are.
 */
final class PhotoUpload {
    interface Listener {
        /** On the main thread, for each photo saved. */
        void added(File photo);
    }

    static final int PORT = 8642;
    private static final int MAX_BYTES = 30 * 1024 * 1024;
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_HEADER = 8192;

    private final File dir;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;

    PhotoUpload(File dir, Listener listener) {
        this.dir = dir;
        this.listener = listener;
    }

    /** The address to open on the phone, or null when the TV is on no network. */
    static String address() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }

    /** Starts listening; false when the port could not be had (the TV keeps working without it). */
    boolean start() {
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            return false;
        }
        new Thread(this::serve, "photo-upload").start();
        return true;
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // Closing it is all that is wanted.
        }
        server = null;
    }

    private void serve() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try (Socket socket = s.accept()) {
                socket.setSoTimeout(TIMEOUT_MS);
                if (!onHomeNetwork(socket.getInetAddress())) continue;
                handle(socket);
            } catch (IOException e) {
                // A closed server ends the loop; a broken request is dropped and the next one served.
            }
        }
    }

    private static boolean onHomeNetwork(InetAddress a) {
        return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress();
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] request = head.split("\r\n")[0].split(" ");
        if (request.length < 2) return;
        String method = request[0], path = request[1];
        if ("GET".equals(method) && ("/".equals(path) || path.startsWith("/?"))) {
            reply(out, 200, "text/html; charset=utf-8", PAGE);
        } else if ("POST".equals(method) && path.startsWith("/upload")) {
            receive(in, out, head, path);
        } else {
            reply(out, 404, "text/plain", "Not found");
        }
    }

    /** The request line and headers, up to the blank line; null if they never end or are too long. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int last4 = 0;
        while (buffer.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            buffer.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) return buffer.toString("ISO-8859-1");
        }
        return null;
    }

    private void receive(InputStream in, OutputStream out, String head, String path) throws IOException {
        long length = contentLength(head);
        if (length <= 0 || length > MAX_BYTES) {
            reply(out, 413, "text/plain", "Too large");
            return;
        }
        String name = safeName(query(path, "name"));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            reply(out, 500, "text/plain", "No room");
            return;
        }
        File part = new File(dir, ".upload.part");
        byte[] first = new byte[12];
        int firstLength = 0;
        try (OutputStream file = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) break;
                if (firstLength < first.length) {
                    int take = Math.min(first.length - firstLength, n);
                    System.arraycopy(buffer, 0, first, firstLength, take);
                    firstLength += take;
                }
                file.write(buffer, 0, n);
                left -= n;
            }
            if (left > 0) throw new IOException("cut short");
        } catch (IOException e) {
            if (!part.delete()) part.deleteOnExit();
            throw e;
        }
        String type = imageType(first, firstLength);
        if (type == null) {
            if (!part.delete()) part.deleteOnExit();
            reply(out, 415, "text/plain", "Not a JPEG, PNG or WebP photo");
            return;
        }
        File photo = unique(dir, withExtension(name, type));
        if (!part.renameTo(photo)) {
            if (!part.delete()) part.deleteOnExit();
            reply(out, 500, "text/plain", "Could not save");
            return;
        }
        reply(out, 200, "text/plain", "OK");
        main.post(() -> listener.added(photo));
    }

    private static long contentLength(String head) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                try {
                    return Long.parseLong(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String query(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** A file name from the phone's, safe on any storage: letters, digits, space, dot, dash and underscore. */
    static String safeName(String name) {
        String base = name == null ? "" : name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(0, dot);   // ".jpg" alone leaves nothing, and gets the default
        base = base.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
        if (base.length() > 60) base = base.substring(0, 60);
        return base.isEmpty() || base.startsWith(".") ? "Photo" : base;
    }

    /** "jpg", "png" or "webp" from the file's first bytes, or null for anything else. */
    static String imageType(byte[] b, int n) {
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        return null;
    }

    private static String withExtension(String base, String type) {
        return base + "." + type;
    }

    /** name, or "name 2", "name 3" and so on when a photo of that name is already there. */
    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        for (int i = 2; f.exists(); i++) f = new File(dir, name.substring(0, dot) + " " + i + name.substring(dot));
        return f;
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 413 ? "Payload Too Large"
                : code == 415 ? "Unsupported Media Type" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + bytes.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    /** The TV's address on the home network: Wi-Fi or Ethernet, IPv4. */
    private static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // No network.
        }
        return null;
    }

    /** The page the phone opens: pick photos, and each is shrunk, turned upright and sent, one at a time. */
    private static final String PAGE = "<!doctype html><html lang=en><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Photos to your TV</title>"
            + "<style>body{margin:0;font:16px -apple-system,system-ui,sans-serif;background:#0b0b0f;color:#f2f2f7;"
            + "display:flex;justify-content:center}main{max-width:460px;width:100%;padding:28px 20px}"
            + "h1{font-size:26px;margin:0 0 6px}p{color:#a1a1aa;margin:0 0 22px;line-height:1.45}"
            + "label{display:block;text-align:center;background:#0a84ff;color:#fff;border-radius:14px;padding:16px;"
            + "font-weight:600;font-size:17px}input{display:none}ul{list-style:none;padding:0;margin:22px 0 0}"
            + "li{display:flex;justify-content:space-between;padding:12px 14px;background:#1c1c22;border-radius:12px;"
            + "margin-bottom:8px;gap:12px}li span:first-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
            + ".ok{color:#30d158}.no{color:#ff453a}</style></head><body><main>"
            + "<h1>Photos to your TV</h1><p>Pick one or more photos. Each one becomes a theme in Settings, Themes, "
            + "Your pictures, where you can choose its clock and tiles.</p>"
            + "<label for=f>Choose photos</label><input id=f type=file accept='image/*' multiple><ul id=l></ul>"
            + "<script>"
            + "const l=document.getElementById('l');"
            + "async function shrink(file){try{const b=await createImageBitmap(file,{imageOrientation:'from-image'});"
            + "const s=Math.min(1,2560/Math.max(b.width,b.height));const c=document.createElement('canvas');"
            + "c.width=Math.round(b.width*s);c.height=Math.round(b.height*s);c.getContext('2d').drawImage(b,0,0,c.width,c.height);"
            + "return await new Promise(r=>c.toBlob(r,'image/jpeg',0.9));}catch(e){return file;}}"
            + "document.getElementById('f').onchange=async e=>{for(const file of e.target.files){"
            + "const li=document.createElement('li');const n=document.createElement('span');n.textContent=file.name;"
            + "const st=document.createElement('span');st.textContent='Sending\\u2026';li.append(n,st);l.prepend(li);"
            + "try{const body=(await shrink(file))||file;const r=await fetch('/upload?name='+encodeURIComponent(file.name),"
            + "{method:'POST',body});st.textContent=r.ok?'On the TV':await r.text();st.className=r.ok?'ok':'no';}"
            + "catch(x){st.textContent='Not sent';st.className='no';}}e.target.value='';};"
            + "</script></main></body></html>";
}
