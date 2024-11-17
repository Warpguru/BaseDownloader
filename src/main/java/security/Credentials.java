package security;

import javax.security.enterprise.credential.Credential;

public class Credentials implements Credential {
	
	private String username;
	private String password;
	private String token;
	
	public Credentials(final String username, final String password, final String token) {
		super();
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
