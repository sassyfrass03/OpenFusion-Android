CC=i686-w64-mingw32-gcc
WINDRES=i686-w64-mingw32-windres
CFLAGS ?= -std=c99 -O2 -g -march=i686 -mtune=generic -fno-strict-aliasing -fno-tree-vectorize -Wall
LDFLAGS ?= -mwindows -static-libgcc -Wl,--large-address-aware,--dynamicbase,--nxcompat
LDLIBS = -lwindowscodecs -lwininet -ldinput8 -ldxguid -lgdi32 -ladvapi32 -lwinmm

SRC=\
	ffrunner.c\
	requests.c\
	graphics.c\
	controller.c\
	logging.c\

HDR=\
	npapi/npapi.h\
	npapi/npfunctions.h\
	npapi/npruntime.h\
	npapi/nptypes.h\
	ffrunner.h\

all: ffrunner.exe

stage: ffrunner.exe
	cp ffrunner.exe ../app/src/main/assets/runner/ffrunner-android.exe

ffrunner.res: ffrunner.rc
	$(WINDRES) ffrunner.rc -O coff -o ffrunner.res

ffrunner.exe: ffrunner.res $(SRC) $(HDR)
	$(CC) $(CFLAGS) $(SRC) -o ffrunner.exe ffrunner.res $(LDFLAGS) $(LDLIBS)

gdbs:
	wine /usr/share/win32/gdbserver.exe localhost:10000 ffrunner.exe

gdbc:
	i686-w64-mingw32-gdb -x gdb.conf

clean:
	rm -rf ffrunner.exe ffrunner.res
