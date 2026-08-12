package security;

import javax.security.enterprise.CallerPrincipal;

public class CredentialsCallerPrincipal extends CallerPrincipal {

	private String username;
	private String password;
	private String token;

	public CredentialsCallerPrincipal(final String username, final String password, final String token) {
		super(username);
		this.username = username;
		this.password = password;
		this.token = token;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public String getToken() {
		return token;
	}

}
