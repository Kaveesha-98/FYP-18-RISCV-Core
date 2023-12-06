module dCacheRegisters #(
  parameter double_word_offset_width = 3, // 2^double_word_offset_width of double words per block
  parameter line_width = 6, // 2^line_wdith of cache lines per set
  localparam tag_width = 32 - double_word_offset_width -3 - line_width, //  
  localparam cache_depth = 1 << line_width,
  localparam block_size = 1 << double_word_offset_width // double words per block
) (
  input [31:0] address,
  output [63:0] byte_aligned_data,
  output [tag_width-1: 0] tag,
  output reg tag_valid,
  input [line_width-1:0] write_line_index,
  input [64*block_size - 1:0] write_block,
  input [tag_width-1: 0] write_tag,
  input [block_size-1:0] write_mask,
  input reset, write_in, clock
);
    wire [127:0] read_out;
    wire [63:0] wea = write_in ? { {8{write_mask[7]}}, {8{write_mask[6]}}, {8{write_mask[5]}}, {8{write_mask[4]}}, {8{write_mask[3]}}, {8{write_mask[2]}}, {8{write_mask[1]}}, {8{write_mask[0]}}} : 64'b0;
    reg old_address_3;
    
    always@(posedge clock)
        old_address_3 <= address[3];

    blk_d_cache d_cache(
    .clka(clock),
    .wea(wea),
    .addra(write_line_index),
    .dina(write_block),
    .clkb(clock),
    .addrb(address[11:4]),
    .doutb(read_out)
  );
  
  //assign byte_aligned_data = address[3] ? read_out[127:64] : read_out[63:0];
  assign byte_aligned_data = old_address_3 ? read_out[127:64] : read_out[63:0];
  
  blk_d_cache_tags d_cache_tags(
      .clka(clock),
      .wea(write_in),
      .addra(write_line_index),
      .dina(write_tag),
      .clkb(clock),
      .addrb(address[11:6]),
      .doutb(tag)
    );
    
    reg [63:0] valid_bits;
    always@(posedge clock) begin
        if (reset) valid_bits <= 64'b0;
        else if (write_in) valid_bits[write_line_index] <= 1'b1;
        tag_valid <= valid_bits[address[11:6]];
    end
//  reg [63:0] cache [cache_depth-1:0][block_size-1:0];
//  reg [tag_width-1:0] tags [cache_depth-1:0];
//  reg validBits [cache_depth-1:0];

//  assign byte_aligned_data = cache[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]][address[double_word_offset_width-1 + 3:3]];
//  assign tag = tags[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]];
//  assign tag_valid = validBits[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]];

//    integer i, j;
//  always @(posedge clock) begin
//    if (reset) begin
//      for (i = 0; i < cache_depth; i=i+1) begin
//        validBits[i] <= 1'b0;
//      end
//    end else if (write_in) begin
//      // partial writes only occur for blocks already in cache
//      for (j = 0; j < block_size; j=j+1) begin
//        if (write_mask[j])begin
//          cache[write_line_index][j] <= write_block[64*j +: 64];
//        end
//      end
//      validBits[write_line_index] <= 1'b1;
//      tags[write_line_index] <= write_tag;
//    end
//  end

endmodule