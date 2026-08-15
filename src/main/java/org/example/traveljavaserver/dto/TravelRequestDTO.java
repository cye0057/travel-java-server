package org.example.traveljavaserver.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class TravelRequestDTO {
    @NotNull(message = "城市不能为空")
    private String city;
    @NotNull(message = "天数不能为空")
    @Min(value = 1,message = "天数不能小于1")
    @Max(value = 30,message = "天数不能大于30")
    private Integer days;
    @NotNull(message = "预算不能为空")
    @Min(value = 100,message = "最小值不能小于100")
    private String budget;
}
