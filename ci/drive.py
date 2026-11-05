# Drives the app's page over WebView debugging, taking screenshots between steps.
import json, subprocess, sys, time, urllib.request
import websocket

OUT = sys.argv[1]
log = open(f"{OUT}/drive.txt", "w")
def say(*a):
    s = " ".join(str(x) for x in a); print(s); log.write(s + "\n"); log.flush()
def adb(*a, **k): return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=k.get("timeout", 60)).stdout
def shot(name):
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    say("shot", name)

pid = adb("shell", "pidof", "com.mymusic.cutter").strip()
say("pid", pid)
adb("forward", "tcp:9222", f"localabstract:webview_devtools_remote_{pid}")
pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json"))
say("pages", [p.get("url") for p in pages])
ws = websocket.create_connection([p for p in pages if "appassets" in p.get("url", "")][0]["webSocketDebuggerUrl"], timeout=8, suppress_origin=True)
mid = [0]
def js(expr):
    mid[0] += 1
    ws.send(json.dumps({"id": mid[0], "method": "Runtime.evaluate", "params": {"expression": expr, "returnByValue": True}}))
    t0 = time.time()
    while True:
        try:
            r = json.loads(ws.recv())
        except Exception as e:
            return f"NO ANSWER ({e}) after {time.time()-t0:.1f}s"
        if r.get("id") == mid[0]:
            return r.get("result", {}).get("result", {}).get("value")

STATE = "(()=>{const L=document.getElementById('videoLayer');return {cls:L.className,title:document.getElementById('videoTitle').textContent,fill:document.getElementById('videoFill').textContent,pos:document.getElementById('cutterPos').textContent,w:innerWidth,h:innerHeight}})()"
def alive(tag):
    say(tag, "page:", js(STATE))
    # is the screen thread answering? (a frozen app does not redraw / answer input)
    t0 = time.time(); out = adb("shell", "dumpsys", "activity", "activities", timeout=30); say(tag, "dumpsys took", round(time.time()-t0, 1))

time.sleep(2)
alive("start"); shot("1_portrait")
say("click Full", js("document.getElementById('videoFull').click();'ok'")); time.sleep(4)
alive("full"); shot("2_full_fit")
say("click Fill", js("document.getElementById('videoFill').click();'ok'")); time.sleep(4)
alive("fill"); shot("3_full_fill")
say("swipe right edge")
size = adb("shell", "wm", "size").split()[-1].split("x"); W, H = int(size[0]), int(size[1])
say("screen", W, H)
long_, short_ = max(W, H), min(W, H)
adb("shell", "input", "swipe", str(long_ - 20), str(short_ * 3 // 4), str(long_ - 20), str(short_ // 4), "600"); time.sleep(1)
say("volume", adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--get").strip()[-80:])
say("click Fill again (Fit)", js("document.getElementById('videoFill').click();'ok'")); time.sleep(4)
alive("fit2"); shot("4_full_fit_again")
say("click Full (exit)", js("document.getElementById('videoFull').click();'ok'")); time.sleep(4)
alive("exit"); shot("5_portrait_again")
say("play", js("document.getElementById('cxPlay').click();'ok'")); time.sleep(3)
alive("playing"); shot("6_playing")
say("Full while playing", js("document.getElementById('videoFull').click();'ok'")); time.sleep(4)
alive("full-playing"); shot("7_full_playing")
say("Fill while playing", js("document.getElementById('videoFill').click();'ok'")); time.sleep(4)
alive("fill-playing"); shot("8_fill_playing")
say("exit while playing", js("document.getElementById('videoFull').click();'ok'")); time.sleep(4)
alive("exit-playing"); shot("9_exit_playing")

for k in range(4):
    say("cycle", k, js("document.getElementById('videoFull').click();'ok'")); time.sleep(3)
    alive(f"cycle{k}-in")
    say("fill toggle", js("document.getElementById('videoFill').click();'ok'")); time.sleep(2)
    alive(f"cycle{k}-fill"); shot(f"c{k}_fill")
    say("exit", js("document.getElementById('videoFull').click();'ok'")); time.sleep(3)
    alive(f"cycle{k}-out")
say("tap on screen"); adb("shell", "input", "tap", "500", "500"); time.sleep(8)
alive("end"); shot("z_end")

# ---- network storage: a file streamed over http (like SMB/FTP file managers do)
say("open network file")
adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "http://10.0.2.2:8000/t.mp4", "-t", "video/mp4", "-n", "com.mymusic.cutter/.MainActivity")
time.sleep(6)
for k in range(3):
    say("net", k, js(STATE)); time.sleep(2)
shot("n1_network")
say("note", js("document.getElementById('cutterNote').textContent"))
# cut a part from the network file
say("mark", js("(()=>{const m=document.querySelector('video')||{};document.getElementById('markStart').click();return 'start'})()"))
time.sleep(3)
say("mark end", js("document.getElementById('markEnd').click();'end'"))
say("save", js("document.getElementById('saveParts').click();'save'"))
for k in range(10):
    time.sleep(2); n = js("document.getElementById('cutterNote').textContent"); say("note", n)
    if n and ("Saved" in n or "failed" in n): break
shot("n2_after_cut")
say("files", adb("shell", "ls", "-R", "/sdcard/Download/MYmusic Cutter"))

# ---- .aac cassette: song finder and saving parts
say("open aac")
adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "file:///sdcard/Download/cassette.aac", "-t", "audio/aac", "-n", "com.mymusic.cutter/.MainActivity")
time.sleep(8)
say("aac", js(STATE))
# new studio effects on during playback: sound must keep running
say("fx on", js("(()=>{[['eqTapeNr',80],['eqBdec',50],['eqHfr',80],['eqBbe',60],['eqMach3',70]].forEach(function(a){var e=document.getElementById(a[0]);e.value=a[1];e.dispatchEvent(new Event('input',{bubbles:true}))});var b=document.getElementById('cxPlay');if(b.textContent.indexOf('▶')>=0)b.click();return b.textContent})()"))
time.sleep(2); p1 = js("document.getElementById('cutterPos').textContent"); time.sleep(3); p2 = js("document.getElementById('cutterPos').textContent")
say("fx playing", p1, "->", p2, js("document.getElementById('videoTitle').textContent"))
say("songs", js("document.getElementById('findSongs').click();document.getElementById('spFind').click();'ok'"))
for k in range(30):
    time.sleep(2); st = js("document.getElementById('spStatus').textContent"); say("status", st)
    if st and ("Found" in st or "No songs" in st or "Could not" in st): break
say("close", js("document.getElementById('spClose').click();'ok'"))
say("save", js("document.getElementById('saveParts').click();'ok'"))
for k in range(20):
    time.sleep(2); n = js("document.getElementById('cutterNote').textContent"); say("note", n)
    if n and ("Saved 3" in n or "failed" in n): break
say("files", adb("shell", "ls", "-lR", "/sdcard/Download/MYmusic Cutter/Audio"))
# fixed seeker: double-tap the seeker handle, then drag the strip
r = js("(()=>{const r=document.getElementById('cutterPlayhead').getBoundingClientRect();return [r.x+r.width/2,r.bottom-12,devicePixelRatio]})()")
say("playhead", r)
if isinstance(r, list):
    x, y = int(r[0]*r[2]), int(r[1]*r[2])
    subprocess.run(["adb", "shell", f"input tap {x} {y}; input tap {x} {y}"], timeout=30)
    time.sleep(1)
    say("locked", js("!document.getElementById('lockHead').hidden"))
    shot("l1_locked")
    sr = js("(()=>{const r=document.getElementById('stripScroll').getBoundingClientRect();return [r.x,r.y+r.height/3,r.width,devicePixelRatio]})()")
    if isinstance(sr, list):
        # the page starts below the status bar: add that offset (screen height - page height - navigation bar)
        ih = js("innerHeight"); scr = adb("shell", "wm", "size").split()[-1].split("x"); sh = int(scr[1])
        nav = sh - int(ih*sr[3])
        top = nav // 2
        say("offset", top, "page", ih)
        y2 = int(sr[1]*sr[3]) + top; x1 = int((sr[0]+sr[2]*0.8)*sr[3]); x2 = int((sr[0]+sr[2]*0.3)*sr[3])
        say("pos before", js("document.getElementById('cutterPos').textContent"))
        say("scroll before", js("document.getElementById('stripScroll').scrollLeft"), "swipe", x1, x2, y2)
        adb("shell", "input", "swipe", str(x1), str(y2), str(x2), str(y2), "900"); time.sleep(2)
        say("pos after drag", js("document.getElementById('cutterPos').textContent"), js("JSON.stringify(window.__mmLockStats)"), js("document.getElementById('stripScroll').scrollLeft"))
        shot("l2_dragged")
