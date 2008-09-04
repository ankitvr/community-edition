/*
 * Copyright (C) 2005-20078 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

 * As a special exception to the terms and conditions of version 2.0 of 
 * the GPL, you may redistribute this Program in connection with Free/Libre 
 * and Open Source Software ("FLOSS") applications as described in Alfresco's 
 * FLOSS exception.  You should have recieved a copy of the text describing 
 * the FLOSS exception, and it is also available here: 
 * http://www.alfresco.com/legal/licensing
 */
package org.alfresco.repo.domain.hibernate;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.util.Pair;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.FlushMode;
import org.hibernate.Query;
import org.hibernate.Session;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

/**
 * This method interceptor determines if a Hibernate flush is required and performs the
 * flush if necessary.  The primary purpose is to avoid the Hibernate "flush if required" checks
 * that occur every time a query is made to the database - whether or not any actual modifications
 * have been made to the session.
 * <p>
 * Write methods (methods that modify the Hibernate Session) will flag the transaction as dirty.
 * Methods that query the database can {@link #setQueryFlushMode(Session, Query) set the flush mode}
 * without knowing whether the session is dirty or not.
 * <p>
 * The interceptor uses the {@link DirtySessionAnnotation}.  If the annotation is not used, then
 * no session dirty checks will be done but a <b>WARN</b> message will be output.
 * <p>
 * The flush data is kept as a transaction-local resource.  For this reason, all calls must be made
 * in the context of a transaction.  For the same reason, the methods on the <b>FlushData</b> are
 * not synchronized as access is only available by one thread.
 * <p>
 * It is also possible to {@link #flushSession(Session) flush the session} manually.  Using this method
 * allows the dirty count to be updated properly, thus avoiding unecessary flushing.
 *
 * @see #setQueryFlushMode(Session, Query)
 * @see #flushSession(Session)
 * 
 * @author Derek Hulley
 * @since 2.1.5
 */
public class DirtySessionMethodInterceptor extends HibernateDaoSupport implements MethodInterceptor
{
    private static final String KEY_FLUSH_DATA = "FlushIfRequiredMethodInterceptor.FlushData";
    
    private static Log logger = LogFactory.getLog(DirtySessionMethodInterceptor.class);
    
    /**
     * Keep track of methods that have been warned about, i.e. methods that are not annotated.
     */
    private static Set<String> unannotatedMethodNames;
    static
    {
        unannotatedMethodNames = Collections.synchronizedSet(new HashSet<String>(0));
    }
    
    /**
     * Data on whether the session is dirty or not.
     * 
     * @author Derek Hulley
     */
    private static class FlushData
    {
        private int dirtyCount;
        private Stack<Pair<String, Boolean>> methodStack;
        private FlushData()
        {
            dirtyCount = 0;
            methodStack = new Stack<Pair<String, Boolean>>();
        }
        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder(64);
            sb.append("FlushData")
              .append("[dirtyCount=").append(dirtyCount)
              .append(", methodStack=").append(methodStack)
              .append("]");
            return sb.toString();
        }
        public void incrementDirtyCount()
        {
            dirtyCount++;
        }
        public boolean isDirty()
        {
            return dirtyCount > 0;
        }
        public void resetDirtyCount()
        {
            dirtyCount = 0;
        }
        public void pushMethod(String methodName, boolean isAnnotated)
        {
            methodStack.push(new Pair<String, Boolean>(methodName, Boolean.valueOf(isAnnotated)));
        }
        public Pair<String, Boolean> popMethod()
        {
            return methodStack.pop();
        }
        public Pair<String, Boolean> currentMethod()
        {
            return methodStack.peek();
        }
        /**
         * @return      Returns <tt>true</tt> if all the methods in the method stack are annotated,
         *              otherwise <tt>false</tt>
         */
        public boolean isStackAnnotated()
        {
            for (Pair<String, Boolean> stackElement : methodStack)
            {
                if (stackElement.getSecond().equals(Boolean.FALSE))
                {
                    // Found one that was not annotated
                    return false;
                }
            }
            // All were annotated
            return true;
        }
    }
    
    /**
     * @return      Returns the transaction-local flush data
     */
    private static FlushData getFlushData()
    {
        FlushData flushData = (FlushData) AlfrescoTransactionSupport.getResource(KEY_FLUSH_DATA);
        if (flushData == null)
        {
            flushData = new FlushData();
            AlfrescoTransactionSupport.bindResource(KEY_FLUSH_DATA, flushData);
        }
        return flushData;
    }
    
    /**
     * Set the query flush mode according to whether the session is dirty or not.
     * 
     * @param session       the Hibernate session
     * @param query         the Hibernate query that will be issued
     */
    public static void setQueryFlushMode(Session session, Query query)
    {
        FlushData flushData = DirtySessionMethodInterceptor.getFlushData();
        
        // If all the methods in the method stack are annotated, then we can adjust the query and
        // play with the session
        if (!flushData.isStackAnnotated())
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Method stack is not annotated.  Not setting query flush mode: \n" +
                        "   Flush Data: " + flushData);
            }
            return;
        }
        
        // The stack is fully annotated, so flush if required and set the flush mode on the query
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Setting query flush mode: \n" +
                    "   Query: " + query.getQueryString() + "\n" +
                    "   Dirty: " + flushData);
        }
        
        if (flushData.isDirty())
        {
            // Flush the session
            session.flush();
            // Reset the dirty state
            flushData.resetDirtyCount();
        }
        // Adjust the query flush mode
        query.setFlushMode(FlushMode.MANUAL);
    }
    
    /**
     * Flush and reset the dirty count for the current transaction.  The session is
     * only flushed if it currently dirty.
     * 
     * @param session           the Hibernate session
     */
    public static void flushSession(Session session)
    {
        flushSession(session, false);
    }
    
    /**
     * Flush and reset the dirty count for the current transaction.
     * Use this one if you know that the session has changeds that might not
     * have been recorded by the DAO interceptors.
     * 
     * @param session           the Hibernate session
     * @param force             <tt>true</tt> to force a flush.
     */
    public static void flushSession(Session session, boolean force)
    {
        FlushData flushData = DirtySessionMethodInterceptor.getFlushData();
        if (force)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Flushing session forcefully: \n" +
                        "   Dirty: " + flushData);
            }
            session.flush();
            flushData.resetDirtyCount();
        }
        else
        {
            if (flushData.isDirty())
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                            "Flushing dirty session: \n" +
                            "   Dirty: " + flushData);
                }
                session.flush();
                flushData.resetDirtyCount();
            }
            else
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                            "Session is not dirty - no flush: \n" +
                            "   Dirty: " + flushData);
                }
            }
        }
    }
    
    /** Default constructor */
    public DirtySessionMethodInterceptor()
    {
    }
    
    public Object invoke(MethodInvocation invocation) throws Throwable
    {
        Method method = invocation.getMethod();
        String methodName = method.getName();
        
        // Get the flush and dirty mark requirements for the call
        DirtySessionAnnotation annotation = method.getAnnotation(DirtySessionAnnotation.class);
        boolean flushBefore = false;
        boolean flushAfter = false;
        boolean markDirty = false;
        if (annotation != null)
        {
            flushBefore = annotation.flushBefore();
            flushAfter = annotation.flushAfter();
            markDirty = annotation.markDirty();
        }
        else if (unannotatedMethodNames.add(methodName))
        {
            logger.warn("Method has not been annotated with the DirtySessionAnnotation: " + method);
        }

        FlushData flushData = DirtySessionMethodInterceptor.getFlushData();
        
        Session session = null;
        if (flushBefore || flushAfter)
        {
            session = getSession(false);
        }
        
        if (flushBefore)
        {
            DirtySessionMethodInterceptor.flushSession(session);
        }

        boolean isAnnotated = (annotation != null);
        Object ret = null;
        try
        {
            // Push the method onto the stack
            flushData.pushMethod(methodName, isAnnotated);
            
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Flush state and parameters for DirtySessionInterceptor: \n" +
                        "   Method:        " + methodName + "\n" +
                        "   Annotated:     BEFORE=" + flushBefore + ", AFTER=" + flushAfter + ", MARK-DIRTY=" + markDirty + "\n" +
                        "   Session State: " + flushData);
            }

            // Do the call
            ret = invocation.proceed();
            
            if (flushAfter)
            {
                DirtySessionMethodInterceptor.flushSession(session);
            }
            else if (markDirty)
            {
                flushData.incrementDirtyCount();
            }
        }
        finally
        {
            // Restore the dirty session awareness state
            flushData.popMethod();
        }
        
        // Done
        return ret;
    }
}
