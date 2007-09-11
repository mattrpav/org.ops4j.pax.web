/*  Copyright 2007 Niclas Hedhman.
 *  Copyright 2007 Alin Dreghiciu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.internal;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.jetty.servlet.ServletHandler;
import org.osgi.service.http.HttpContext;

public class HttpServiceHandler extends ServletHandler
{

    private RegistrationsCluster m_registrationsCluster;
    private static ThreadLocal<HttpContext> m_activeHttpContext;

    public HttpServiceHandler( final RegistrationsCluster registrationsCluster )
    {
        m_registrationsCluster = registrationsCluster;
        m_activeHttpContext = new ThreadLocal<HttpContext>();
    }

    @Override
    public void handle( final String target, final HttpServletRequest request, final HttpServletResponse response,
                        final int dispatchMode )
        throws IOException, ServletException
    {
        String trimmedtarget = trimTrailingChars( target, '/' );
        handle( trimmedtarget, trimmedtarget, request, response, dispatchMode );
    }

    private void handle( final String requestTarget, final String target, final HttpServletRequest request,
                         final HttpServletResponse response, final int dispatchMode )
        throws IOException, ServletException
    {
        String match = target;
        boolean handled = false;
        while( !"".equals( match ) && !handled )
        {
            Registration registration = m_registrationsCluster.getByAlias( match );
            if( registration != null )
            {
                HttpContext httpContext = registration.getHttpContext();
                if( httpContext.handleSecurity( request, response ) )
                {
                    try
                    {
                        request.setAttribute( ResourceServlet.REQUEST_HANDLED, Boolean.TRUE );
                        setActiveHttpContext( httpContext );
                        super.handle( target, request, response, dispatchMode );
                    } finally
                    {
                        removeActiveHttpContext();
                        Boolean handledAttr = (Boolean) request.getAttribute( ResourceServlet.REQUEST_HANDLED );
                        if( handledAttr.booleanValue() )
                        {
                            handled = true;
                        }
                    }
                }
                else
                {
                    // on case of security constraints not fullfiled,
                    // handleSecurity is supposed to set the right headers
                    return;
                }
            }
            match = match.substring( 0, match.lastIndexOf( "/" ) );
        }
        // if still not handled try out "/"
        if( !handled && !"/".equals( target ) )
        {
            handle( requestTarget, "/", request, response, dispatchMode );
            return;
        }
        if( !handled )
        {
            response.sendError( HttpServletResponse.SC_NOT_FOUND );
        }
    }

    /**
     * @param target string
     * @param c      character to be trimmed at the end of target (example: '/')
     *
     * @return a trimed string with reduced trailing characters
     */
    String trimTrailingChars( String target, char c )
    {
        char[] seq = target.toCharArray();
        if( seq[ seq.length - 1 ] == c )
        {
            for( int cursor = seq.length - 1; cursor >= 0; cursor-- )
            {
                if( seq[ cursor ] != c )
                {
                    // plus 2 is allowed because we know there is one more
                    // (first condition in method)
                    // and we are just at the first non slash
                    // and it is necessary to ensure consistence with expected
                    // match flow
                    // hint: we just trim trailing slashes, so /foo// becomes
                    // /foo/
                    return target.substring( 0, cursor + 2 );
                }
                else if( cursor == 0 )
                {
                    // all slashes (like "//////") if c = '/' (for example)
                    return "" + c;
                }
            }
        }
        return target;
    }

    private static void setActiveHttpContext( final HttpContext httpContext )
    {
        m_activeHttpContext.set( httpContext );
    }

    public static HttpContext getActiveHttpContext()
    {
        return m_activeHttpContext.get();
    }

    private static void removeActiveHttpContext()
    {
        m_activeHttpContext.remove();
    }

}
