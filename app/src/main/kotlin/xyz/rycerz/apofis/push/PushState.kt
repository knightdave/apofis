package xyz.rycerz.apofis.push

sealed interface PushState {
    data object Off : PushState
    data object NoDistributor : PushState
    data object Registering : PushState
    data class On(val distributor: String) : PushState
    data class ServerError(val distributor: String) : PushState
}

data class PushUi(val state: PushState)
