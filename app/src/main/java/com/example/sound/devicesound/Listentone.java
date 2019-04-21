package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import calsualcoding.reedsolomon.EncoderDecoder;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone() {

        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();

    }

    public ArrayList<Integer> decode_bitchunks(int chunk_bits, ArrayList<Integer> chunks) {
        int next_read_chunk = 0;
        int next_read_bit = 0;
        // byte chunk_bits;
        int next_chunk;
        int out_byte = 0;
        ArrayList<Integer> out_bytes = new ArrayList<>();
        int byte_chunk = 0;
        int bits_left = 8;
        while (next_read_chunk < chunks.size()) {
            int can_fill = chunk_bits - next_read_bit;
            int to_fill = Math.min(bits_left, can_fill);
            int offset = chunk_bits - next_read_bit - to_fill;
            out_byte <<= to_fill;
            int shifted = chunks.get(next_read_chunk) & (((1 << to_fill) - 1) << offset);
            out_byte |= shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;
            if (bits_left <= 0) {
                out_bytes.add(out_byte);
                byte_chunk = 0;
                bits_left = 8;
            }
            if (next_read_bit >= chunk_bits) {
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
            }
        }
        return out_bytes;
    }


    public void PreRequest() {
        int blocksize = findPowerSize((int) (long) Math.round(interval / 2 * mSampleRate));
        Log.d("blocksize : ", String.valueOf(blocksize));
        short[] buffer = new short[blocksize];

        boolean in_packet = false;
        ArrayList<Double> packet = new ArrayList<>();
        double dom=0;

        while (true) {
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize); //2048
            double[] realBuffer = new double[buffer.length];
            if (bufferedReadResult == 2048) {
                for (int i = 0; i < buffer.length; i++) {
                    realBuffer[i] = (double) buffer[i];
      //          Log.d("realBuffer : ", Double.toString(realBuffer[i]));

                }
                dom = findfrequency(realBuffer);
//                Log.d("Dom :", Double.toString(dom));
                if (in_packet && match(dom, HANDSHAKE_END_HZ)) {
                    ArrayList<Byte> byte_stream = new ArrayList<>();
                    byte_stream = extract_packet(packet);
                    try {
                        //bytearray를 바꾸어 주어야 string으로 바뀔 수 있다.
                        byte[] realByte = new byte[byte_stream.size()];
                        for (int i = 0; i < byte_stream.size(); i++) {
                            realByte[i] = byte_stream.get(i);
                        }
                        String realString = new String(realByte, "UTF-8");
                        Log.d("[REAL STRING] : ", realString);
                        for (int i = 0; i < packet.size(); i++) {
                            packet.remove(i);
                        }
                        in_packet = false;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else if (in_packet) {
                    packet.add(dom);
                } else if (match(dom, HANDSHAKE_START_HZ)) {
                    Log.d("Message : ", "Handshake!!!!");
                    in_packet = true;
                }
            }
        }
    }


    private boolean match(double freq1, double freq2) {
        return Math.abs(freq1 - freq2) < 20;
    }

    private int findPowerSize(int value) {
        // value랑 가장 가까운 2의 제곱수를 반환
        double finalValue = 0;
        double i = 0;
        double currentValue = Math.pow(2, i);
        while (value > currentValue) {
            i++;
            currentValue =  Math.pow(2, i);
        }
        double prevValue = currentValue / 2;
        double prevsub = value - prevValue;
        double nextsub = currentValue - value;
        if (prevsub < nextsub) {
            finalValue = prevValue;
        } else {
            finalValue = currentValue;
        }
        return (int)finalValue;
    }

    public Double[] fftfreq(int a, double b) {
        Double[] function = new Double[a];
        int c = 0;
        if (a % 2 == 0) {
            for (int i = 0; i <= (a / 2) - 1; i++) {
                function[c] = i * (b / a);
                c++;
            }
            for (int i = -a / 2; i <= -1; i++) {
                function[c] = i * (b / a);
                c++;
            }
        } else {
            for (int i = 0; i <= (a - 1) / 2; i++) {
                function[c] = i * (b / a);
                c++;
            }
            for (int i = -(a - 1) / 2; i <= -1; i++) {
                function[c] = i * (b / a);
                c++;
            }
        }

        return function;
    }

    private double findfrequency(double[] toTransform) {
        // decode.py의 dominant함수
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD);
        Double[] freq = this.fftfreq(complx.length, 1);
//        for (int i = 0; i < freq.length; i++){
//            Log.d("프리 : ", String.valueOf(freq[i]));
//
//        }

        for (int i = 0; i < complx.length; i++) {
            realNum = complx[i].getReal();
            imgNum = complx[i].getImaginary();
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));
            // mag : amplitude
        }
        int peak_coeff = 0; // 최대 value의 배열 인덱스를 찾는 값
        double magValue = mag[0];
        for (int i = 1; i < complx.length; i++) {
            if (magValue < mag[i]) {
                magValue = mag[i];
                peak_coeff = i;
            }
        }
        double peak_freq = freq[peak_coeff];
//        Log.d("돔 : ", Double.toString(peak_freq));
        return Math.abs(peak_freq * mSampleRate);
    }

    private ArrayList<Byte> extract_packet(ArrayList<Double> freq) {
        ArrayList<Integer> bit_chunks = new ArrayList<>();
        ArrayList<Integer> bit_chunks_new = new ArrayList<>();
        for (int i = 0; i < freq.size(); i = i + 2) {
            bit_chunks.add((int) (Math.round(freq.get(i) - START_HZ) / STEP_HZ));
        }
        for (int i = 1; i < bit_chunks.size(); i++) {
            int c = bit_chunks.get(i);
            if ((c >= 0) && c < Math.pow(2, BITS)) {
                bit_chunks_new.add(c);
            }
        }
        ArrayList<Integer> aArrayList = decode_bitchunks(BITS, bit_chunks_new);
        ArrayList<Byte> bArrayList = new ArrayList<>();
        for (int i = 0; i < aArrayList.size(); i++) {
            byte[] aByte = ConvertByteArray(aArrayList.get(i));
            for (int j = 0; j < aByte.length; j++) {
                bArrayList.add(aByte[j]);
            }
        }
        return bArrayList;
    }

    public byte[] ConvertByteArray(Integer value) {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(value);
        return bb.array();
    }

}

