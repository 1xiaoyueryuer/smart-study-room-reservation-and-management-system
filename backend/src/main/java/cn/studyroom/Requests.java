package cn.studyroom;

import javax.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class Requests {
    private Requests() {}
    public record Complaint(@NotBlank @Pattern(regexp="occupation|service") String type,@JsonProperty("reservation_id") @Positive Long reservationId,@JsonProperty("room_id") @Positive Long roomId,
                            @NotBlank @Size(max=2000) String description,@JsonProperty("attachment_ids") java.util.List<String> attachmentIds,@JsonProperty("operation_id") @NotBlank String operationId) {}
    public record ResourceChangeProposal(@JsonProperty("change_type") @NotBlank String changeType,@JsonProperty("target_id") @NotNull @Positive Long targetId,@NotNull java.util.Map<String,Object> payload) {}
    public record Coordination(Boolean confirmed,String note) {}
    public record ResourceChangeConfirm(@javax.validation.Valid @NotNull ResourceChangeProposal proposal,@JsonProperty("impact_hash") @NotBlank String impactHash,@JsonProperty("operation_id") @NotBlank String operationId,Coordination coordination) {}
    public record SeatCodeChange(@NotNull @Min(0) Long version,@NotBlank @Size(max=1000) String reason) {}
    public record SeatCodeStatus(@NotNull @Positive Long version,@NotNull Boolean enabled,@NotBlank @Size(max=1000) String reason) {}
    public record Login(@NotBlank String username,@NotBlank String password) {}
    public record Register(@NotBlank @Size(min=3,max=50) String username,@NotBlank @Size(min=6,max=64) String password,
                           @JsonProperty("real_name") @NotBlank @Size(max=50) String realName,@JsonProperty("student_no") @Size(max=20) String studentNo) {}
    public record Room(@NotBlank @Size(max=100) String name,@Size(max=200) String location,
                       @JsonProperty("open_time") String openTime,@JsonProperty("close_time") String closeTime,
                       @JsonProperty("seat_rows") @Min(1) @Max(30) int seatRows,@JsonProperty("seat_cols") @Min(1) @Max(30) int seatCols,
                       @Size(max=5000) String description,@Size(max=100) String building,@Size(max=30) String floor,
                       @NotNull Boolean enabled,@Positive Long version,@NotBlank @Size(max=1000) String reason) {}
    public record Batch(@JsonProperty("seat_rows") @Min(1) @Max(30) int seatRows,@JsonProperty("seat_cols") @Min(1) @Max(30) int seatCols,
                        @NotNull @Positive Long version,@NotBlank @Size(max=1000) String reason) {}
    public record Seat(@NotBlank @Pattern(regexp="quiet|regular|discussion|computer") String zone,
                       @JsonProperty("has_power") @NotNull Boolean hasPower,@JsonProperty("near_window") @NotNull Boolean nearWindow,
                       @NotBlank @Pattern(regexp="available|maintenance|disabled") String status,
                       @JsonProperty("seat_no") @NotBlank @Size(max=20) String seatNo,
                       @JsonProperty("row_no") @Min(1) int rowNo,@JsonProperty("col_no") @Min(1) int colNo,
                       @NotNull @Positive Long version,@NotBlank @Size(max=1000) String reason) {}
    public record ResourceDelete(@NotNull @Positive Long version,@NotBlank @Size(max=1000) String reason) {}
    public record OpeningWindow(@NotBlank String start,@NotBlank String end) {}
    public record OpeningChange(Integer weekday,String date,@NotNull java.util.List<@javax.validation.Valid OpeningWindow> windows,
                                @NotNull @Positive Long version,@NotBlank @Size(max=1000) String reason) {}
    public record OpeningDelete(@NotBlank String date,@NotNull @Positive Long version,@NotBlank @Size(max=1000) String reason) {}
    public record Unavailability(@JsonProperty("seat_id") @Positive Long seatId,@JsonProperty("start_at") @NotBlank String startAt,
                                 @JsonProperty("end_at") @NotBlank String endAt,@NotNull @Positive Long version,@NotBlank @Size(max=1000) String reason) {}
    public record Status(@NotBlank @Pattern(regexp="active|disabled") String status,@NotBlank @Size(max=1000) String reason) {}
    public record UserChange(@NotBlank @Size(min=3,max=50) String username,
            @JsonProperty("real_name") @NotBlank @Size(max=50) String realName,
            @JsonProperty("campus_id") @Size(max=20) String campusId,
            @NotBlank @Pattern(regexp="student|staff|admin") String role,
            @NotBlank @Pattern(regexp="active|disabled") String status,
            @Size(max=30) String phone,@Email @Size(max=254) String email,
            @Size(max=64) String password,@NotBlank @Size(max=1000) String reason) {}
    public record Contacts(@Size(max=30) String phone,@Email @Size(max=254) String email) {}
    public record Reservation(@JsonProperty("seat_id") @Positive long seatId,@NotBlank String date,@JsonProperty("start_time") @NotBlank String startTime,@JsonProperty("end_time") @NotBlank String endTime) {}
    public record Allocation(@JsonProperty("room_id") @Positive long roomId,@NotBlank String date,@JsonProperty("start_time") @NotBlank String startTime,@JsonProperty("end_time") @NotBlank String endTime,
                             @Pattern(regexp="|quiet|regular|discussion|computer") String zone,@JsonProperty("has_power") Boolean hasPower,@JsonProperty("near_window") Boolean nearWindow,
                             @JsonProperty("top_n") @Min(0) @Max(10) int topN,@JsonProperty("auto_book") boolean autoBook) {}
    public record Wait(@JsonProperty("room_id") @Positive long roomId,@NotBlank String date,@JsonProperty("start_time") @NotBlank String startTime,@JsonProperty("end_time") @NotBlank String endTime,
                       @Pattern(regexp="|quiet|regular|discussion|computer") String zone,@JsonProperty("has_power") Boolean hasPower,@JsonProperty("near_window") Boolean nearWindow) {}
    public record ComplaintAccept(Long version,@JsonProperty("operation_id") String operationId) {}
    public record ComplaintVerification(Long version,@JsonProperty("operation_id") String operationId,String outcome,
            @JsonProperty("occurred_at") String occurredAt,@JsonProperty("public_note") String publicNote,@JsonProperty("private_note") String privateNote,
            @JsonProperty("subject_user_id") Long subjectUserId,@JsonProperty("identity_confirmed") Boolean identityConfirmed,
            @JsonProperty("identity_basis") String identityBasis,@JsonProperty("facts_confirmed") Boolean factsConfirmed,
            @JsonProperty("valid_reservation_confirmed") Boolean validReservationConfirmed) {}
    public record ComplaintFeedback(Long version,@JsonProperty("operation_id") String operationId,String result,String basis) {}
    public record ComplaintResponse(Long version,@JsonProperty("operation_id") String operationId,String description) {}
}
