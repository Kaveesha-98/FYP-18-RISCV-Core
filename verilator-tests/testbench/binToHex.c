#include <stdio.h>
#include <stdlib.h>

int main(int argc, char *argv[]) {
  if (argc < 3) {
    printf("Usage: %s input_file output_file\n", argv[0]);
    return 1;
  }

  FILE *input_file = fopen(argv[1], "rb");
  if (!input_file) {
    printf("Error: could not open input file %s\n", argv[1]);
    return 1;
  }

  FILE *output_file = fopen(argv[2], "w");
  if (!output_file) {
    printf("Error: could not open output file %s\n", argv[2]);
    fclose(input_file);
    return 1;
  }

  unsigned char buffer[4096];
  int bytes_read;

  fprintf(output_file, "@0\n"); // write address to output file

  while ((bytes_read = fread(buffer, 1, sizeof(buffer), input_file)) > 0) {
    for (int i = 0; i < bytes_read; i++) {
      fprintf(output_file, "%02x ", buffer[i]); // write byte to output file in hexadecimal format
    }
  }

  fclose(input_file);
  fclose(output_file);

  return 0;
}