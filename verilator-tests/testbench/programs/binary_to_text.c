#include <stdio.h>

int main(int argc, char* argv[]) {
    if (argc < 3) {
        printf("Usage: %s input_file output_file\n", argv[0]);
        return 1;
    }
    
    FILE* input_file = fopen(argv[1], "rb");
    if (input_file == NULL) {
        printf("Error opening input file\n");
        return 1;
    }
    
    FILE* output_file = fopen(argv[2], "w");
    if (output_file == NULL) {
        printf("Error opening output file\n");
        fclose(input_file);
        return 1;
    }
    
    int byte;
    while ((byte = fgetc(input_file)) != EOF) {
        fprintf(output_file, "0x%02X,", byte);
    }
    
    fclose(input_file);
    fclose(output_file);
    return 0;
}
