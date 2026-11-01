package dev.chiron.chiroru

internal val ManagerStep.messageResource: Int get() = when (this) {
    ManagerStep.CHECKING -> R.string.manager_checking
    ManagerStep.DOWNLOADING -> R.string.manager_downloading
    ManagerStep.VERIFYING -> R.string.manager_verifying
    ManagerStep.INSTALLING -> R.string.manager_installing
    ManagerStep.INSTALLED -> R.string.manager_installed
    ManagerStep.ALREADY_INSTALLED -> R.string.manager_already_installed
    ManagerStep.ERROR -> R.string.manager_error
}
