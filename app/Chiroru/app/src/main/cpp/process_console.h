#ifndef CHIRORU_PROCESS_CONSOLE_H
#define CHIRORU_PROCESS_CONSOLE_H
int process_console_pending(void);
int process_console_bootstrap(void);
int process_console_ready(void);
int process_console_verify(void);
void process_console_close(void);
#endif
