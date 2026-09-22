"""Run actual parser and window lifecycle functions on a host C compiler.

Win32 client-rectangle reads are stubbed here. This does not emulate Wine,
Unity, GPU rendering, or an Android display.
"""
import re
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
header = (root/'ffrunner.h').read_text()
main = (root/'ffrunner.c').read_text()
graphics = (root/'graphics.c').read_text()
assert 'DXVK_FORCE_WINDOWED' in main
controller = (root/'controller.c').read_text()
assert 'GetWindowThreadProcessId(foreground, &pid)' in controller
assert 'pid == GetCurrentProcessId()' in controller
assert 'IsWindowVisible(controllerWindow) && !IsIconic(controllerWindow)' in controller
# User-requested keyboard/mouse controller layout.
for snippet in [
    "send_key(VK_SPACE, (g->wButtons & XBTN_A) != 0)",
    "send_key('1', (g->wButtons & XBTN_X) != 0)",
    "send_key('2', (g->wButtons & XBTN_Y) != 0)",
    "send_key('3', (g->wButtons & XBTN_B) != 0)",
    "send_key('X', (g->wButtons & XBTN_LEFT_SHOULDER) != 0)",
    "send_key(VK_TAB, (g->wButtons & XBTN_RIGHT_SHOULDER) != 0)",
    "send_mouse_button(false, rtDown)",
    "send_mouse_button(true, ltDown)",
    "send_key('M', (g->wButtons & XBTN_BACK) != 0)",
    "send_return_scancode((g->wButtons & XBTN_START) != 0)",
    "send_key('R', (g->wButtons & XBTN_DPAD_UP) != 0)",
    "send_key('C', (g->wButtons & XBTN_DPAD_DOWN) != 0)",
    "send_key('I', (g->wButtons & XBTN_DPAD_LEFT) != 0)",
    "send_key('V', (g->wButtons & XBTN_DPAD_RIGHT) != 0)",
]: assert snippet in controller, snippet
assert 'SetTimer(window, RESIZE_TIMER_ID, 75, NULL)' in graphics
assert 'void\nrefresh_plugin_window(void)' in graphics
assert 'EnumChildWindows(hwnd, find_process_unity_window' in graphics
assert 'L"UnityWindow"' in graphics
assert 'SetParent(render, NULL)' in graphics
assert 'WS_POPUP | WS_CLIPCHILDREN | WS_CLIPSIBLINGS' in graphics
assert 'UNITY_WINDOW_TIMER_ID' in graphics
display_case = graphics[graphics.index('case WM_DISPLAYCHANGE:'):graphics.index('    }\n    if (ioMsg', graphics.index('case WM_DISPLAYCHANGE:'))]
assert 'SetWindowPos' not in display_case or 'BORDERLESS_FULLSCREEN' in display_case
assert 'if (args.windowMode == BORDERLESS_FULLSCREEN)' in display_case
startup = main[main.index('int\nmain(int argc'): ]
assert startup.index('ret = pluginFuncs.newp(') < startup.index('ShowWindow(') < startup.index('pluginWindowReady = true') < startup.index('update_plugin_window(false)')
arguments = header[header.index('struct Arguments {'):header.index('\nextern Arguments args;')]
mode = re.search(r'enum WindowMode \{[^}]+\};', header).group()
defines = '\n'.join(line for line in header.splitlines() if line.startswith(('#define FALLBACK_', '#define DEFAULT_', '#define FFRUNNER_BUILD', '#define LOG_FILE_PATH')))
common = '#include <stdint.h>\n#include <stdbool.h>\n#include <stdlib.h>\n#include <stdio.h>\n#include <string.h>\n#include <errno.h>\n#include <assert.h>\n'+mode+'\n'+defines+'\n'+arguments+'\ntypedef struct Arguments Arguments;\n'+re.search(r'Arguments args = [^;]+;', main).group()+'\n'
parser = main[main.index('static void\nusage('):main.index('\nvoid\nprint_args()')]
parser_main = '''
int main(int argc, char **argv) {
    parse_args(argc, argv);
    printf("%d %u %u %u %d %d\\n", args.windowMode, args.windowWidth, args.windowHeight,
        args.vramMb, args.forceOpenGl, args.queryVram);
    return 0;
}
'''
# The lifecycle functions are compiled verbatim from graphics.c.
lifecycle = 'HWND hwnd;\nbool pluginWindowReady;\nstatic bool settingPluginWindow, pluginWindowAttached;\n' + graphics[graphics.index('void\nupdate_plugin_window'):graphics.index('\nstatic bool\nwindow_geometry')]
shim = '''
#define NPERR_NO_ERROR 0
#define FALSE 0
typedef int NPError;
#define logmsg(...) ((void)0)
typedef void *HWND;
typedef struct { int32_t left, top, right, bottom; } RECT;
typedef struct { void *window; int32_t x,y; uint32_t width,height;
  struct {uint16_t top,left,bottom,right;} clipRect; } NPWindow;
int npp;
NPWindow npWin;
struct { int (*setwindow)(void *, NPWindow *); } pluginFuncs;
static RECT client={0,0,960,540};
static int calls, detaches, depth;
void update_plugin_window(bool minimized);
int GetClientRect(HWND window, RECT *rect) { (void)window; *rect=client; return 1; }
int IsIconic(HWND window) { (void)window; return 0; }
int InvalidateRect(HWND window, void *rect, int erase) { (void)window;(void)rect;(void)erase;return 1; }
int UpdateWindow(HWND window) { (void)window; return 1; }
int set_window(void *instance, NPWindow *window) {
    (void)instance;
    if(window) {
        assert(depth==0);depth++;
        calls++;
        update_plugin_window(false); /* Win32 reentry from inside Unity */
        depth--;
    } else detaches++;
    return 0;
}
'''
lifecycle_main = '''
int main(void) {
    hwnd=(void*)1;
    update_plugin_window(false);  /* callback pointer is not initialized */
    pluginFuncs.setwindow=set_window;
    update_plugin_window(false);  /* plugin instance is not ready */
    npWin.window=hwnd;
    update_plugin_window(false);
    assert(calls==0);
    pluginWindowReady=true;
    update_plugin_window(false);
    assert(calls==1 && npWin.width==960 && npWin.height==540);
    assert(npWin.clipRect.right==960 && npWin.clipRect.bottom==540);
    update_plugin_window(false);update_plugin_window(false);
    assert(calls==1); /* identical window messages must not reset the surface */
    refresh_plugin_window();
    assert(calls==2); /* fullscreen -> browser return must force same-size reattach */
    client.right=0;client.bottom=0;
    update_plugin_window(false);
    assert(calls==2 && npWin.width==960 && npWin.height==540);
    update_plugin_window(true);
    assert(calls==3 && npWin.clipRect.right==0 && npWin.clipRect.bottom==0);
    client.right=1280;client.bottom=720;
    update_plugin_window(false);
    assert(npWin.width==1280 && npWin.height==720 && npWin.clipRect.right==1280);
    detach_plugin_window();detach_plugin_window();update_plugin_window(false);
    assert(detaches==1 && calls==4 && !pluginWindowReady);
    puts("window lifecycle assertions passed");
}
'''
with tempfile.TemporaryDirectory() as temp:
    temp=Path(temp)
    def build(name, source):
        src=temp/(name+'.c');out=temp/name;src.write_text(source)
        subprocess.run(['gcc','-std=c99','-Wall','-Wextra','-Werror',str(src),'-o',str(out)],check=True)
        return out
    exe=build('args',common+parser+parser_main)
    good=[([], '0 1280 720 0 0 0'),(['--auth-url','http://127.0.0.1:4000/token/__auth','--xinput-bridge'],'0 1280 720 0 0 0'),(['--windowed','--width','960','--height=540'],'0 960 540 0 0 0'),(['--borderless-window','--width=1280','--height','720'],'1 1280 720 0 0 0'),(['--borderless','--force-opengl','--vram-mb','768'],'2 1280 720 768 1 0'),(['--query-vram'],'0 1280 720 0 0 1')]
    for params,wanted in good:
        assert subprocess.check_output([str(exe),*params],text=True).strip()==wanted,params
    bad=[['--width'],['--width','-1'],['--height','0'],['--width','999999999999999999999'],['--width','1280x'],['--borderless=1'],['--force-opengl','--force-vulkan'],['--vram-mb','1'],['--loader-images'],['--bogus','x']]
    for params in bad:
        result=subprocess.run([str(exe),*params],capture_output=True,text=True)
        assert result.returncode==2,(params,result.returncode)
    for params in [['--help'],['--version']]:
        assert subprocess.run([str(exe),*params],capture_output=True).returncode==0
    window=build('lifecycle',common+shim+lifecycle+lifecycle_main)
    subprocess.run([str(window)],check=True)
print('PASS: 18 argument cases; requested controller map; Enter/Return scan-code injection; recursive top-level Unity browser renderer hooks; fullscreen-safe focus; window lifecycle; resize debounce; and display-change handling.')
