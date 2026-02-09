package global.goldenera.node.explorer.api.v1.common.dtos;

import java.util.List;

import global.goldenera.node.explorer.api.v1.account.dtos.AccountBalanceDtoV1;
import global.goldenera.node.explorer.api.v1.addressalias.dtos.AddressAliasDtoV1;
import global.goldenera.node.explorer.api.v1.authority.dtos.AuthorityDtoV1;
import global.goldenera.node.explorer.api.v1.blockheader.dtos.BlockHeaderDtoV1;
import global.goldenera.node.explorer.api.v1.memtransfer.dtos.MemTransferDtoV1;
import global.goldenera.node.explorer.api.v1.token.dtos.TokenDtoV1;
import global.goldenera.node.explorer.api.v1.tx.dtos.TxDtoV1;
import global.goldenera.node.explorer.api.v1.validator.dtos.ValidatorDtoV1;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchDtoV1 {

    private List<BlockHeaderDtoV1> blocks;
    private List<TxDtoV1> transactions;
    private List<MemTransferDtoV1> mempoolTransactions;
    private List<AccountBalanceDtoV1> accounts;
    private List<TokenDtoV1> tokens;
    private List<ValidatorDtoV1> validators;
    private List<AuthorityDtoV1> authorities;
    private List<AddressAliasDtoV1> aliases;
    private long count;

}
