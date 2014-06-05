/*
 * Copyright (C) 2005-2013 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.share.dashlet;

import static org.alfresco.share.util.ShareUser.openSiteDashboard;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.alfresco.po.share.dashlet.SiteContentDashlet;
import org.alfresco.po.share.dashlet.SiteContentFilter;
import org.alfresco.po.share.dashlet.sitecontent.SimpleViewInformation;
import org.alfresco.po.share.enums.Dashlets;
import org.alfresco.po.share.site.document.DocumentDetailsPage;
import org.alfresco.po.share.site.document.DocumentLibraryPage;
import org.alfresco.share.util.AbstractUtils;
import org.alfresco.share.util.ShareUser;
import org.alfresco.share.util.ShareUserDashboard;
import org.alfresco.share.util.api.CreateUserAPI;
import org.alfresco.webdrone.testng.listener.FailedTestListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

/**
 * @author Shan Nagarajan
 */
@Listeners(FailedTestListener.class)
public class RecentlyModifiedDashletTest extends AbstractUtils
{

    private static Log logger = LogFactory.getLog(RecentlyModifiedDashletTest.class);

    @Override
    @BeforeClass(alwaysRun = true)
    public void setup() throws Exception
    {
        super.setup();
        testName = this.getClass().getSimpleName();
        logger.info("Start Tests in: " + testName);
    }

    @Test(groups = { "DataPrepDashlets" })
    public void dataPrep_Dashlets_7935() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String siteName = getSiteName(testName);
        String fileName = getFileName(testName) + ".txt";

        // User
        String[] testUserInfo = new String[] { testUser };
        CreateUserAPI.CreateActivateUser(drone, ADMIN_USERNAME, testUserInfo);

        // Login
        ShareUser.login(drone, testUser, DEFAULT_PASSWORD);

        // Create Site
        ShareUser.createSite(drone, siteName, SITE_VISIBILITY_PUBLIC);

        // Upload File
        String[] fileInfo = { fileName, DOCLIB };
        DocumentLibraryPage documentLibraryPage = ShareUser.uploadFileInFolder(drone, fileInfo);
        DocumentDetailsPage detailsPage = documentLibraryPage.selectFile(fileName).render();
        detailsPage.selectFavourite().render();

    }

    @Test
    public void Enterprise40x_7935()
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String siteName = getSiteName(testName);
        String fileName = getFileName(testName) + ".txt";

        // Login
        ShareUser.login(drone, testUser, DEFAULT_PASSWORD);

        // Open Site DashBoard
        openSiteDashboard(drone, siteName);

        SiteContentDashlet siteContentDashlet = ShareUserDashboard.getDashlet(drone, Dashlets.SITE_CONTENT).render();

        siteContentDashlet.selectFilter(SiteContentFilter.MY_FAVOURITES);

        siteContentDashlet.clickSimpleView();

//        for (int i = 0; i < retrySearchCount; i++)
//        {
//            try
//            {
//                siteContentDashlet.renderSimpleViewWithContent();
//            }
//            catch (PageRenderTimeException e)
//            {
//                // There is Exception going retry again.
//                continue;
//            }
//            break;
//        }

        siteContentDashlet.renderSimpleViewWithContent();

        List<SimpleViewInformation> informations = siteContentDashlet.getSimpleViewInformation();
        SimpleViewInformation simpleViewInformation = informations.get(0);

        assertTrue(simpleViewInformation.getContentStatus().contains("Created"));
        assertTrue(simpleViewInformation.isPreviewDisplayed());
        assertEquals(simpleViewInformation.getContentDetail().getDescription().trim(), fileName.trim());
        assertNotNull(simpleViewInformation.getThumbnail());

        // Test amended for https://issues.alfresco.com/jira/browse/ALF-20348
        // assertTrue(simpleViewInformation.getUser().toString().contains(testUser));
        //
        // MyProfilePage profilePage = simpleViewInformation.clickUser();
        // assertTrue(profilePage.titlePresent());

        openSiteDashboard(drone, siteName);
        
        siteContentDashlet = ShareUserDashboard.getDashlet(drone, Dashlets.SITE_CONTENT).render();
        informations = siteContentDashlet.getSimpleViewInformation();
        simpleViewInformation = informations.get(0);

        DocumentDetailsPage detailsPage = simpleViewInformation.clickContentDetail();
        assertTrue(detailsPage.isDocumentDetailsPage());

    }

}
