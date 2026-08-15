package org.example.traveljavaserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor

public class StreamChunkVO {
    private String type = "chunk";
    private String content;

    public static StreamChunkVO of(String content){
        return new StreamChunkVO("chunk",content);
    }

}
