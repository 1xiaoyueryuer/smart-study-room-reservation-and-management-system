package cn.studyroom;

import javax.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class Requests {
    private Requests() {}
    public record Login(@NotBlank String username,@NotBlank String password) {}
    public record Register(@NotBlank @Size(min=3,max=50) String username,@NotBlank @Size(min=6,max=64) String password,
                           @JsonProperty("real_name") @NotBlank @Size(max=50) String realName,@JsonProperty("student_no") @Size(max=20) String studentNo) {}
    public record Room(@NotBlank @Size(max=100) String name,@Size(max=200) String location,
                       @JsonProperty("open_time") @NotBlank String openTime,@JsonProperty("close_time") @NotBlank String closeTime,@JsonProperty("seat_rows") @Min(1) @Max(30) int seatRows,
                       @JsonProperty("seat_cols") @Min(1) @Max(30) int seatCols,@Size(max=5000) String description) {}
    public record Batch(@JsonProperty("seat_rows") @Min(1) @Max(30) int seatRows,@JsonProperty("seat_cols") @Min(1) @Max(30) int seatCols) {}
    public record Seat(@NotBlank @Pattern(regexp="quiet|regular|discussion|computer") String zone,@JsonProperty("has_power") Boolean hasPower,@JsonProperty("near_window") Boolean nearWindow,
                       @Pattern(regexp="available|maintenance|disabled") String status) {}
    public record Status(@NotBlank @Pattern(regexp="active|disabled") String status) {}
    public record Reservation(@JsonProperty("seat_id") @Positive long seatId,@NotBlank String date,@JsonProperty("start_time") @NotBlank String startTime,@JsonProperty("end_time") @NotBlank String endTime) {}
    public record Allocation(@JsonProperty("room_id") @Positive long roomId,@NotBlank String date,@JsonProperty("start_time") @NotBlank String startTime,@JsonProperty("end_time") @NotBlank String endTime,
                             @Pattern(regexp="|quiet|regular|discussion|computer") String zone,@JsonProperty("has_power") Boolean hasPower,@JsonProperty("near_window") Boolean nearWindow,
                             @JsonProperty("top_n") @Min(0) @Max(10) int topN,@JsonProperty("auto_book") boolean autoBook) {}
    public record Wait(@JsonProperty("room_id") @Positive long roomId,@NotBlank String date,@JsonProperty("start_time") @NotBlank String startTime,@JsonProperty("end_time") @NotBlank String endTime,
                       @Pattern(regexp="|quiet|regular|discussion|computer") String zone,@JsonProperty("has_power") Boolean hasPower,@JsonProperty("near_window") Boolean nearWindow) {}
}
