/* SPDX-License-Identifier: MIT
 * sdl-port-probe: a stand-in PortMaster "port" that exercises the full ports
 * userspace on the freed display: SDL2 with the KMSDRM video driver, a GLES2
 * context through libmali, and controller/keyboard input. It is launched the
 * same way a real port is (after the framework-down handoff, inside the glibc
 * rootfs), so if this draws and takes input, real SDL ports will too.
 *
 * Renders an animated clear colour, flashes brighter on any button/key, prints
 * every input event it sees (so we can confirm the console pad reaches it, both
 * natively via SDL and via gptokeyb's virtual keyboard), and exits on START or
 * ESC or after a timeout. GL entry points are loaded with SDL_GL_GetProcAddress
 * so the binary links only against SDL2.
 */
#include <SDL.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

typedef void (*fp_clearcolor)(float, float, float, float);
typedef void (*fp_clear)(unsigned int);
typedef void (*fp_viewport)(int, int, int, int);
typedef const unsigned char *(*fp_getstring)(unsigned int);
#define GL_COLOR_BUFFER_BIT 0x00004000u
#define GL_VENDOR 0x1F00
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02

int main(int argc, char **argv)
{
    unsigned seconds = (argc > 1) ? (unsigned)strtoul(argv[1], NULL, 10) : 20;
    if (seconds < 1 || seconds > 600) seconds = 20;
    setvbuf(stdout, NULL, _IOLBF, 0);

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_JOYSTICK | SDL_INIT_GAMECONTROLLER) != 0) {
        fprintf(stderr, "FAIL SDL_Init: %s\n", SDL_GetError());
        return 2;
    }
    printf("SDL video driver=%s\n", SDL_GetCurrentVideoDriver());

    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 2);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);

    SDL_Window *win = SDL_CreateWindow("andr36oid-port-probe", 0, 0, 640, 480,
            SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN_DESKTOP);
    if (!win) { fprintf(stderr, "FAIL SDL_CreateWindow: %s\n", SDL_GetError()); SDL_Quit(); return 3; }
    SDL_GLContext ctx = SDL_GL_CreateContext(win);
    if (!ctx) { fprintf(stderr, "FAIL SDL_GL_CreateContext: %s\n", SDL_GetError()); SDL_Quit(); return 3; }
    SDL_GL_SetSwapInterval(1);

    fp_clearcolor glClearColor = (fp_clearcolor)SDL_GL_GetProcAddress("glClearColor");
    fp_clear glClear = (fp_clear)SDL_GL_GetProcAddress("glClear");
    fp_viewport glViewport = (fp_viewport)SDL_GL_GetProcAddress("glViewport");
    fp_getstring glGetString = (fp_getstring)SDL_GL_GetProcAddress("glGetString");
    if (!glClear || !glClearColor || !glGetString) {
        fprintf(stderr, "FAIL GL entry points not resolved\n"); SDL_Quit(); return 4;
    }
    printf("GL vendor=%s renderer=%s version=%s\n",
           glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION));

    int w = 0, h = 0; SDL_GL_GetDrawableSize(win, &w, &h);
    if (w > 0 && h > 0) glViewport(0, 0, w, h);
    printf("DRAWABLE %dx%d\n", w, h);

    /* open any attached controllers so native pad events flow. */
    int pads = 0;
    for (int i = 0; i < SDL_NumJoysticks(); i++) {
        if (SDL_IsGameController(i)) { if (SDL_GameControllerOpen(i)) pads++; }
        else SDL_JoystickOpen(i);
    }
    printf("INPUT joysticks=%d controllers_opened=%d\n", SDL_NumJoysticks(), pads);

    Uint32 start = SDL_GetTicks();
    Uint32 flash_until = 0;
    int running = 1, inputs = 0;
    while (running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            switch (e.type) {
            case SDL_QUIT: running = 0; break;
            case SDL_KEYDOWN:
                inputs++;
                printf("KEYDOWN sym=%d name=%s\n", e.key.keysym.sym, SDL_GetKeyName(e.key.keysym.sym));
                if (e.key.keysym.sym == SDLK_ESCAPE || e.key.keysym.sym == SDLK_RETURN) running = 0;
                flash_until = SDL_GetTicks() + 200;
                break;
            case SDL_CONTROLLERBUTTONDOWN:
                inputs++;
                printf("PAD button=%d (%s)\n", e.cbutton.button,
                       SDL_GameControllerGetStringForButton((SDL_GameControllerButton)e.cbutton.button));
                if (e.cbutton.button == SDL_CONTROLLER_BUTTON_START) running = 0;
                flash_until = SDL_GetTicks() + 200;
                break;
            case SDL_JOYBUTTONDOWN:
                inputs++;
                printf("JOY button=%d\n", e.jbutton.button);
                flash_until = SDL_GetTicks() + 200;
                break;
            default: break;
            }
        }
        float t = (SDL_GetTicks() - start) / 1000.0f;
        int flash = SDL_GetTicks() < flash_until;
        glClearColor(flash ? 1.0f : 0.25f + 0.25f * sinf(t * 2.0f),
                     flash ? 1.0f : 0.15f + 0.2f * sinf(t * 1.3f + 1.0f),
                     flash ? 1.0f : 0.3f + 0.3f * sinf(t * 0.7f + 2.0f), 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        SDL_GL_SwapWindow(win);
        if (SDL_GetTicks() - start > seconds * 1000u) running = 0;
    }
    printf("DONE inputs_seen=%d elapsed=%ums\n", inputs, SDL_GetTicks() - start);

    SDL_GL_DeleteContext(ctx);
    SDL_DestroyWindow(win);
    SDL_Quit();
    puts("EXIT status=0");
    return 0;
}
