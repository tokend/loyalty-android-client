package org.tokend.template.activities

import android.graphics.drawable.ColorDrawable
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import com.mikepenz.materialdrawer.AccountHeader
import com.mikepenz.materialdrawer.AccountHeaderBuilder
import com.mikepenz.materialdrawer.DrawerBuilder
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem
import com.mikepenz.materialdrawer.model.ProfileDrawerItem
import org.tokend.template.R
import org.tokend.template.features.clients.view.CompanyClientsFragment
import org.tokend.template.features.settings.SettingsFragment
import org.tokend.template.util.ProfileUtil

class CorporateMainActivity : MainActivity() {
    override val defaultFragmentId = CompanyClientsFragment.ID

    override fun getNavigationItems(): List<PrimaryDrawerItem> {
        return mutableListOf(
                PrimaryDrawerItem()
                        .withName(R.string.clients_title)
                        .withIdentifier(CompanyClientsFragment.ID)
                        .withIcon(R.drawable.ic_accounts)
        ).apply { addAll(super.getNavigationItems()) }
    }

    override fun addRequiredNavigationItems(builder: DrawerBuilder,
                                            items: Map<Long, PrimaryDrawerItem>) {
        builder.apply {
            addDrawerItems(items[CompanyClientsFragment.ID])
            addDrawerItems(items[SettingsFragment.ID])
        }
    }

    override fun getHeaderInstance(email: String?): AccountHeader {
        return AccountHeaderBuilder()
                .withActivity(this)
                .withHeaderBackground(
                        ColorDrawable(ContextCompat.getColor(this, R.color.white))
                )
                .withTextColor(ContextCompat.getColor(this, R.color.primary_text))
                .withSelectionListEnabledForSingleProfile(false)
                .withProfileImagesVisible(true)
                .withDividerBelowHeader(true)
                .addProfiles(getProfileHeaderItem(email))
                .withOnAccountHeaderListener { _, _, _ ->
                    openAccountIdShare()
                    false
                }
                .build()
    }

    override fun getProfileHeaderItem(email: String?): ProfileDrawerItem {
        val kycState = kycStateRepository.item
        val avatarUrl = ProfileUtil.getAvatarUrl(kycState, urlConfigProvider, email)

        return ProfileDrawerItem()
                .withIdentifier(1)
                .withName(email)
                .withEmail(getString(R.string.kyc_form_type_corporate))
                .apply {
                    avatarUrl?.also { withIcon(it) }
                }
    }

    override fun getCompaniesProfileItems(): Collection<ProfileDrawerItem> = emptyList()

    override fun getFragment(screenIdentifier: Long): Fragment? {
        return super.getFragment(screenIdentifier) ?: when (screenIdentifier) {
            CompanyClientsFragment.ID -> factory.getCompanyClientsFragment()
            else -> null
        }
    }
}