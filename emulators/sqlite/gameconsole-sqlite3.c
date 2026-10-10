/*
 * The stock sqlite3 shell, built without the Android functions.
 *
 * Android's sqlite3 registers ICU collators when it opens a database and quits
 * when that fails, and it fails for the gameconsole-emu service
 * ("register_localized_collators() failed"), so the service could neither
 * read nor fill Daijishou's library. Daijishou's tables don't use those
 * collators, so the shell is built here as for the host: NO_ANDROID_FUNCS,
 * linked against libsqlite_static_noicu.
 */
#define NO_ANDROID_FUNCS 1
#include "shell.c"
