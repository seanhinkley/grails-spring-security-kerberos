/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.extensions.kerberos.web;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.extensions.kerberos.KerberosServiceAuthenticationProvider;
import org.springframework.security.extensions.kerberos.KerberosServiceRequestToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.Assert;
import org.springframework.web.filter.GenericFilterBean;

/**
 * Parses the SPNEGO authentication Header, which was generated by the browser
 * and creates a {@link KerberosServiceRequestToken} out if it. It will then
 * call the {@link AuthenticationManager}.
 *
 * <p>
 * A typical Spring Security configuration might look like this:
 * </p>
 *
 * <pre>
 * &lt;beans xmlns=&quot;http://www.springframework.org/schema/beans&quot;
 * xmlns:xsi=&quot;http://www.w3.org/2001/XMLSchema-instance&quot; xmlns:sec=&quot;http://www.springframework.org/schema/security&quot;
 * xsi:schemaLocation=&quot;http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-2.0.xsd
 * 	http://www.springframework.org/schema/security http://www.springframework.org/schema/security/spring-security-3.0.xsd&quot;&gt;
 *
 * &lt;sec:http entry-point-ref=&quot;spnegoEntryPoint&quot;&gt;
 * 	&lt;sec:intercept-url pattern=&quot;/secure/**&quot; access=&quot;IS_AUTHENTICATED_FULLY&quot; /&gt;
 * 	&lt;sec:custom-filter ref=&quot;spnegoAuthenticationProcessingFilter&quot; position=&quot;BASIC_AUTH_FILTER&quot; /&gt;
 * &lt;/sec:http&gt;
 *
 * &lt;bean id=&quot;spnegoEntryPoint&quot; class=&quot;org.springframework.security.extensions.kerberos.web.SpnegoEntryPoint&quot; /&gt;
 *
 * &lt;bean id=&quot;spnegoAuthenticationProcessingFilter&quot;
 * 	class=&quot;org.springframework.security.extensions.kerberos.web.SpnegoAuthenticationProcessingFilter&quot;&gt;
 * 	&lt;property name=&quot;authenticationManager&quot; ref=&quot;authenticationManager&quot; /&gt;
 * &lt;/bean&gt;
 *
 * &lt;sec:authentication-manager alias=&quot;authenticationManager&quot;&gt;
 * 	&lt;sec:authentication-provider ref=&quot;kerberosServiceAuthenticationProvider&quot; /&gt;
 * &lt;/sec:authentication-manager&gt;
 *
 * &lt;bean id=&quot;kerberosServiceAuthenticationProvider&quot;
 * 	class=&quot;org.springframework.security.extensions.kerberos.KerberosServiceAuthenticationProvider&quot;&gt;
 * 	&lt;property name=&quot;ticketValidator&quot;&gt;
 * 		&lt;bean class=&quot;org.springframework.security.extensions.kerberos.SunJaasKerberosTicketValidator&quot;&gt;
 * 			&lt;property name=&quot;servicePrincipal&quot; value=&quot;HTTP/web.springsource.com&quot; /&gt;
 * 			&lt;property name=&quot;keyTabLocation&quot; value=&quot;classpath:http-java.keytab&quot; /&gt;
 * 		&lt;/bean&gt;
 * 	&lt;/property&gt;
 * 	&lt;property name=&quot;userDetailsService&quot; ref=&quot;inMemoryUserDetailsService&quot; /&gt;
 * &lt;/bean&gt;
 *
 * &lt;bean id=&quot;inMemoryUserDetailsService&quot;
 * 	class=&quot;org.springframework.security.core.userdetails.memory.InMemoryDaoImpl&quot;&gt;
 * 	&lt;property name=&quot;userProperties&quot;&gt;
 * 		&lt;value&gt;
 * 			mike@SECPOD.DE=notUsed,ROLE_ADMIN
 * 		&lt;/value&gt;
 * 	&lt;/property&gt;
 * &lt;/bean&gt;
 * &lt;/beans&gt;
 * </pre>
 *
 * If you get a "GSSException: Channel binding mismatch (Mechanism
 * level:ChannelBinding not provided!) have a look at this <a
 * href="http://bugs.sun.com/view_bug.do?bug_id=6851973">bug</a>.<br />
 * A workaround unti this is fixed in the JVM is to change
 * HKEY_LOCAL_MACHINE\System
 * \CurrentControlSet\Control\LSA\SuppressExtendedProtection to 0x02
 *
 *
 * Note: this is the version from commit http://git.springsource.org/spring-security/se-security/commit/cec872c5c82d2864ad9bf49497cf325e88ccfddb
 * and fixes GPSPRINGSECURITYKERBEROS-4 and GPSPRINGSECURITYKERBEROS-6 - remove when spring-security-kerberos M3 is available.
 *
 *
 * @author Mike Wiesner
 * @since 1.0
 * @version $Id$
 * @see KerberosServiceAuthenticationProvider
 * @see SpnegoEntryPoint
 */
public class SpnegoAuthenticationProcessingFilter extends GenericFilterBean {

    private AuthenticationManager authenticationManager;
    private AuthenticationSuccessHandler successHandler;
    private AuthenticationFailureHandler failureHandler;
    private boolean skipIfAlreadyAuthenticated = true;


    /*
     * (non-Javadoc)
     *
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
     * javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if (skipIfAlreadyAuthenticated) {
            Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();

            if (existingAuth != null && existingAuth.isAuthenticated()
                    && (existingAuth instanceof AnonymousAuthenticationToken) == false) {
                chain.doFilter(request, response);
                return;
            }
        }

        String header = request.getHeader("Authorization");

        if ((header != null) && header.startsWith("Negotiate ")) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received Negotiate Header for request " + request.getRequestURL() + ": " + header);
            }
            byte[] base64Token = header.substring(10).getBytes("UTF-8");
            byte[] kerberosTicket = Base64.decode(base64Token);
            KerberosServiceRequestToken authenticationRequest = new KerberosServiceRequestToken(kerberosTicket);
            Authentication authentication;
            try {
                authentication = authenticationManager.authenticate(authenticationRequest);
            } catch (AuthenticationException e) {
                // That shouldn't happen, as it is most likely a wrong
                // configuration on the server side
                logger.warn("Negotiate Header was invalid: " + header, e);
                SecurityContextHolder.clearContext();
                if (failureHandler != null) {
                    failureHandler.onAuthenticationFailure(request, response, e);
                } else {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.flushBuffer();
                }
                return;
            }
            if (successHandler != null) {
                successHandler.onAuthenticationSuccess(request, response, authentication);
            }
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        chain.doFilter(request, response);

    }

    /**
     * The authentication manager for validating the ticket.
     *
     * @param authenticationManager
     */
    public void setAuthenticationManager(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * This handler is called after a successful authentication. One can add
     * additional authentication behavior by setting this.<br />
     * Default is null, which means nothing additional happens
     *
     * @param successHandler
     */
    public void setSuccessHandler(AuthenticationSuccessHandler successHandler) {
        this.successHandler = successHandler;
    }

    /**
     * This handler is called after a failure authentication. In most cases you
     * only get Kerberos/SPNEGO failures with a wrong server or network
     * configurations and not during runtime. If the client encounters an error,
     * he will just stop the communication with server and therefore this
     * handler will not be called in this case.<br />
     * Default is null, which means that the Filter returns the HTTP 500 code
     *
     * @param failureHandler
     */
    public void setFailureHandler(AuthenticationFailureHandler failureHandler) {
        this.failureHandler = failureHandler;
    }


    /**
     * Should Kerberos authentication be skipped if a user is already authenticated
     * for this request (e.g. in the HTTP session).
     *
     * @param skipIfAlreadyAuthenticated default is true
     */
    public void setSkipIfAlreadyAuthenticated(boolean skipIfAlreadyAuthenticated) {
        this.skipIfAlreadyAuthenticated = skipIfAlreadyAuthenticated;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.springframework.web.filter.GenericFilterBean#afterPropertiesSet()
     */
    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();
        Assert.notNull(this.authenticationManager, "authenticationManager must be specified");
    }
}
