// Copyright (c) 2012 Cloudera, Inc. All rights reserved.

#ifndef IMPALA_EXEC_HDFS_SEQUENCE_SCANNER_H
#define IMPALA_EXEC_HDFS_SEQUENCE_SCANNER_H

#include "util/codec.h"
#include "exec/hdfs-scanner.h"
#include "exec/delimited-text-parser.h"

namespace impala {

// This scanner parses Sequence file located in HDFS, and writes the
// content as tuples in the Impala in-memory representation of data, e.g.
// (tuples, rows, row batches).
// org.apache.hadoop.io.SequenceFile is the original SequenceFile implementation
// and should be viewed as the canonical definition of this format. If
// anything is unclear in this file you should consult the code in
// org.apache.hadoop.io.SequenceFile.
//
// The following is a pseudo-BNF grammar for SequenceFile. Comments are prefixed
// with dashes:
//
// seqfile ::=
//   <file-header>
//   <record-block>+
//
// record-block ::=
//   <record>+
//   <file-sync-hash>
//
// file-header ::=
//   <file-version-header>
//   <file-key-class-name>
//   <file-value-class-name>
//   <file-is-compressed>
//   <file-is-block-compressed>
//   [<file-compression-codec-class>]
//   <file-header-metadata>
//   <file-sync-field>
//
// file-version-header ::= Byte[4] {'S', 'E', 'Q', 6}
//
// -- The name of the Java class responsible for reading the key buffer
//
// file-key-class-name ::=
//   Text {"org.apache.hadoop.io.BytesWritable"}
//
// -- The name of the Java class responsible for reading the value buffer
//
// -- We don't care what this is.
// file-value-class-name ::=
//
// -- Boolean variable indicating whether or not the file uses compression
// -- for key/values in this file
//
// file-is-compressed ::= Byte[1]
//
// -- A boolean field indicating whether or not the file is block compressed.
//
// file-is-block-compressed ::= Byte[1] {false}
//
// -- The Java class name of the compression codec iff <file-is-compressed>
// -- is true. The named class must implement
// -- org.apache.hadoop.io.compress.CompressionCodec.
// -- The expected value is org.apache.hadoop.io.compress.GzipCodec.
//
// file-compression-codec-class ::= Text
//
// -- A collection of key-value pairs defining metadata values for the
// -- file. The Map is serialized using standard JDK serialization, i.e.
// -- an Int corresponding to the number of key-value pairs, followed by
// -- Text key and value pairs.
//
// file-header-metadata ::= Map<Text, Text>
//
// -- A 16 byte marker that is generated by the writer. This marker appears
// -- at regular intervals at the beginning of records or record blocks
// -- intended to enable readers to skip to a random part of the file
// -- the sync hash is preceeded by a length of -1, refered to as the sync marker
//
// file-sync-hash ::= Byte[16]
//
// -- Records are all of one type as determined by the compression bits in the header
//
// record ::=
//   <uncompressed-record>     |
//   <block-compressed-record> |
//   <record-compressed-record>
//
// uncompressed-record ::=
//   <record-length>
//   <key-length>
//   <key>
//   <value>
//
// record-compressed-record ::=
//   <record-length>
//   <key-length>
//   <key>
//   <compressed-value>
//
// block-compessed-record ::=
//   <file-sync-field>
//   <key-lengths-block-size>
//   <key-lengths-block>
//   <keys-block-size>
//   <keys-block>
//   <value-lengths-block-size>
//   <value-lengths-block>
//   <values-block-size>
//   <values-block>
//
// record-length := Int
// key-length := Int
// keys-lengths-block-size> := Int
// value-lengths-block-size> := Int
//
// keys-block :: = Byte[keys-block-size]
// values-block :: = Byte[values-block-size]
//
// -- The key-lengths and value-lengths blocks are are a sequence of lengths encoded
// -- in ZeroCompressedInteger (VInt) format.
//
// key-lengths-block :: = Byte[key-lengths-block-size]
// value-lengths-block :: = Byte[value-lengths-block-size]
//
// Byte ::= An eight-bit byte
//
// VInt ::= Variable length integer. The high-order bit of each byte
// indicates whether more bytes remain to be read. The low-order seven
// bits are appended as increasingly more significant bits in the
// resulting integer value.
//
// Int ::= A four-byte integer in big-endian format.
//
// Text ::= VInt, Chars (Length prefixed UTF-8 characters)

class HdfsSequenceScanner : public HdfsScanner {
 public:
  // The four byte SeqFile version header present at the beginning of every
  // SeqFile file: {'S', 'E', 'Q', 6}
  static const uint8_t SEQFILE_VERSION_HEADER[4];

  HdfsSequenceScanner(HdfsScanNode* scan_node, RuntimeState* state);

  virtual ~HdfsSequenceScanner();
  
  // Implementation of HdfsScanner interface.
  virtual Status Prepare();
  virtual Status ProcessScanRange(ScanRangeContext* context);
  virtual Status Close();

  // Issue the initial scan ranges for all sequence files.
  static void IssueInitialRanges(HdfsScanNode*, const std::vector<HdfsFileDesc*>&);

 private:
  // Sync indicator
  const static int SYNC_MARKER = -1;

  // Size of the sync hash field
  const static int SYNC_HASH_SIZE = 16;

  // The value class name located in the SeqFile Header.
  // This is always "org.apache.hadoop.io.Text"
  static const char* const SEQFILE_VALUE_CLASS_NAME;

  // The key should always be 4 bytes.
  static const int SEQFILE_KEY_LENGTH;

  // Estimate of header size in bytes.  Headers are likely on remote nodes.  If
  // this is not big enough, the scanner will read more as necessary.
  static const int HEADER_SIZE;

  void IssueFileRanges(const char* filename);

  Status InitNewRange();
  Status ProcessRange();

  // Writes the intermediate parsed data in to slots, outputting
  // tuples to row_batch as they complete.
  // Input Parameters:
  //  state: Runtime state into which we log errors
  //  row_batch: Row batch into which to write new tuples
  //  num_fields: Total number of fields contained in parsed_data_
  // Input/Output Parameters
  //  row_idx: Index of current row in row_batch.
  Status WriteFields(MemPool*, TupleRow*, int num_fields, bool* add_row);

  // Find the first record of a scan range.
  // If the scan range is not at the beginning of the file then this is called to
  // move the buffered_byte_stream_ seek point to before the next sync field.
  Status FindFirstRecord(bool* found);

  // Read the current Sequence file header from the begining of the file.
  // Verifies:
  //   version number
  //   key and data classes
  // Sets:
  //   is_compressed_
  //   is_blk_compressed_
  //   compression_codec_
  //   sync_
  Status ReadFileHeader();

  // Read the Sequence file Header Metadata section in the current file.
  // We don't use this information, so it is just skipped.
  Status ReadFileHeaderMetadata();

  // Read and validate a RowGroup sync field.
  Status ReadSync();

  // Read the record header, return if there was a sync block.
  // Sets:
  //   current_block_length_
  Status ReadBlockHeader(bool* sync);

  // Read compressed blocks and iterate through the records in each block.
  // Output:
  //   record_ptr: ponter to the record.
  //   record_len: length of the record
  //   eors: set to true if we are at the end of the scan range.
  Status GetRecordFromCompressedBlock(uint8_t** record_ptr, int64_t *record_len);

  // Read compressed or uncompressed records from the byte stream into memory
  // in unparsed_data_buffer_pool_.
  // Output:
  //   record_ptr: ponter to the record.
  //   record_len: length of the record
  //   eors: set to true if we are at the end of the scan range.
  Status GetRecord(uint8_t** record_ptr, int64_t *record_len);

  // Read a compressed block.
  // Decompress to unparsed_data_buffer_ allocated from unparsed_data_buffer_pool_.
  Status ReadCompressedBlock();

  // read and verify a sync block.
  Status CheckSync();

  // Context for this scanner.  The context maps to a single scan range.
  ScanRangeContext* context_;

  // Helper class for picking fields and rows from delimited text.
  boost::scoped_ptr<DelimitedTextParser> delimited_text_parser_;
  std::vector<FieldLocation> field_locations_;

  // Helper class for converting text fields to internal types.
  boost::scoped_ptr<TextConverter> text_converter_;

  // Data that is fixed across headers.  This struct is shared between scan ranges.
  struct FileHeader {
    // The sync hash read in from the file header.
    uint8_t sync[SYNC_HASH_SIZE];

    // File compression or not.
    bool is_compressed;
    // Block compression or not.
    bool is_blk_compressed;

    // Codec name if it is compressed.
    std::string codec;
  
    // End of the header block so we don't have to reparse it.
    int64_t header_size;
  };

  // If true, this scanner is only processing the header bytes.
  bool only_parsing_header_;

  // Header for this scan range.  Memory is owned by the parent scan node.
  FileHeader* header_;

  // The decompressor class to use.
  boost::scoped_ptr<Codec> decompressor_;

  // Length of the current sequence file block (or record).
  int current_block_length_;

  // Length of the current key.  This should always be SEQFILE_KEY_LENGTH.
  int current_key_length_;

  // Pool for allocating the unparsed_data_buffer_.
  boost::scoped_ptr<MemPool> unparsed_data_buffer_pool_;

  // Buffer for data read from HDFS or from decompressing the HDFS data.
  uint8_t* unparsed_data_buffer_;

  // Number of buffered records unparsed_data_buffer_ from block compressed data.
  int64_t num_buffered_records_in_compressed_block_;

  // Next record from block compressed data.
  uint8_t* next_record_in_compressed_block_;
};

} // namespace impala

#endif // IMPALA_EXEC_HDFS_SEQUENCE_SCANNER_H
