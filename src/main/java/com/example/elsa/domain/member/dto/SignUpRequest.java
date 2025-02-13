package com.example.elsa.domain.member.dto;

import com.example.elsa.domain.member.entity.Role;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignUpRequest {
	@Schema(description = "이메일", example = "test1234@gmail.com", required = true)
	@NotBlank(message = "이메일은 필수 입력 값입니다.")
	@Email(message = "유효한 이메일 주소를 입력해주세요.")
	private String email;

	@Schema(description = "비밀번호는 최소 8자 이상, 영문자, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.", example = "!test1234", required = true)
	@NotBlank(message = "비밀번호는 필수 입력 값입니다.")
	@Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*#?&])[A-Za-z\\d@$!%*#?&]{8,}$", message = "비밀번호는 최소 8자 이상, 영문자, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.")
	private String password;

	@Schema(description = "이름은 2자 이상 10자 이하로 입력해주세요.", example = "민교수", required = true)
	@NotBlank(message = "이름은 필수 입력 값입니다.")
	@Size(min = 2, max = 10, message = "이름은 2자 이상 10자 이하로 입력해주세요.")
	private String name;

	@Schema(description = "회원의 역할", example = "ADMIN", required = true)
	@NotNull(message = "역할은 필수 입력 값입니다.")
	private Role role;

	@Schema(description = "직업", example = "학생", required = true)
	@NotNull(message = "직업은 필수 입력 값입니다.")
	private String career;

	@Schema(description = "국가", example = "한국", required = true)
	@NotNull(message = "국가는 필수 입력 값입니다.")
	private String country;
}
