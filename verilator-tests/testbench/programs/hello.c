int main()
{
  char prompt[] = "Hello world!\n";
  int *uart = 0xe0001030;
  int *uart_status = 0xe000102c;
  
  while(1) {
    while(!((*uart_status)&8))
      ;

    for(int i = 0; i < 13; i++)
      *uart = (int) prompt[i];
  }
  return 0;
}