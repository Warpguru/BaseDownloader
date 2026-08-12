package security;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.security.enterprise.AuthenticationException;
import javax.security.enterprise.AuthenticationStatus;
import javax.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import javax.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import javax.security.enterprise.identitystore.CredentialValidationResult;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@ApplicationScoped
public class AuthenticationMechanism implements HttpAuthenticationMechanism {

	@Override
	public AuthenticationStatus validateRequest(final HttpServletRequest httpServletRequest,
			final HttpServletResponse httpServletResponse, final HttpMessageContext httpMessageContext)
			throws AuthenticationException {
		Credentials credentials = getCredentials(httpServletRequest);
		CredentialValidationResult credentialValidationResult = validate(credentials);
		if (credentialValidationResult.getStatus() == CredentialValidationResult.Status.VALID) {
//			try {
//				httpServletRequest.login(credentials.getUsername(), credentials.getPassword());
//			} catch (ServletException e) {
//				e.printStackTrace();
//			}
			return httpMessageContext.notifyContainerAboutLogin(credentialValidationResult);
		}
//		try {
//			httpServletRequest.logout();
//		} catch (ServletException e) {
//			e.printStackTrace();
//		}
		return httpMessageContext.responseUnauthorized();
	}

	private Credentials getCredentials(final HttpServletRequest httpServletRequest) {
		Map<String, String[]> mapQueryParameters = httpServletRequest.getParameterMap();
		httpServletRequest.getParameter("username");
		httpServletRequest.getParameter("password");
		httpServletRequest.getParameter("token");
		if (mapQueryParameters != null) {
			String username = null;
			String password = null;
			String token = null;
			String[] usernames = mapQueryParameters.get("username");
			if ((usernames != null) && (usernames.length == 1) && (usernames[0].trim().length() > 0)) {
				username = usernames[0];
			}
			String[] passwords = mapQueryParameters.get("password");
			if ((passwords != null) && (passwords.length == 1) && (passwords[0].trim().length() > 0)) {
				password = passwords[0];
			}
			String[] tokens = mapQueryParameters.get("token");
			if ((tokens != null) && (tokens.length == 1) && (tokens[0].trim().length() > 0)) {
				token = tokens[0];
			}
			Credentials credentials = new Credentials(username, password, token);
			return credentials;
		}
		return null;
	}

	public CredentialValidationResult validate(final Credentials credentials) {
		if (!(credentials instanceof Credentials)) {
			return CredentialValidationResult.INVALID_RESULT;
		}
		if ((credentials.getUsername() == null) || (credentials.getPassword() == null) || (credentials.getToken() == null)) {
			return CredentialValidationResult.INVALID_RESULT;
		}
		Set<String> roles = new HashSet<>();
		roles.add("Admin");
		CredentialsCallerPrincipal credentialsCallerPrincipal = new CredentialsCallerPrincipal(credentials.getUsername(),
				credentials.getPassword(), credentials.getToken());
		CredentialValidationResult credentialValidationResult = new CredentialValidationResult(credentialsCallerPrincipal,
				roles);
		return credentialValidationResult;
	}

}
